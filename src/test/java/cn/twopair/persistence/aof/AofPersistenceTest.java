package cn.twopair.persistence.aof;

import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试AOF持久化协调器的刷盘策略、Rewrite和关闭语义。
 *
 * @author ljj
 */
public class AofPersistenceTest {
	/**
	 * 验证Rewrite不接受空的Redis内存数据库。
	 *
	 * @throws Exception AOF创建或清理失败时抛出
	 */
	@Test
	public void testRewriteRejectsNullRedisCore() throws Exception {
		Path path = Files.createTempFile("miniredis-rewrite-null-core-", ".aof");

		try (AofPersistence persistence = new AofPersistence(path, AofFsyncPolicy.ALWAYS)) {
			NullPointerException exception = Assert.assertThrows(NullPointerException.class, () -> persistence.rewriteAsync(null));
			Assert.assertEquals("RedisCore不能为空", exception.getMessage());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证没有真实文件路径的测试替身不能执行Rewrite。
	 *
	 * @throws Exception 协调器关闭失败时抛出
	 */
	@Test
	public void testRewriteRequiresRealAofPath() throws Exception {
		RecordingAofStorage storage = new RecordingAofStorage();

		try (AofPersistence persistence = new AofPersistence(storage, AofFsyncPolicy.ALWAYS, 1000L)) {
			IllegalStateException exception = Assert.assertThrows(IllegalStateException.class, () -> persistence.rewriteAsync(new RedisCoreImpl()));
			Assert.assertEquals("当前AOF存储没有真实文件路径", exception.getMessage());
		}
	}

	/**
	 * 验证AOF持久化协调器关闭后不能再启动Rewrite。
	 *
	 * @throws Exception AOF创建、关闭或清理失败时抛出
	 */
	@Test
	public void testRewriteRejectedAfterClose() throws Exception {
		Path path = Files.createTempFile("miniredis-rewrite-closed-", ".aof");
		AofPersistence persistence = new AofPersistence(path, AofFsyncPolicy.ALWAYS);

		try {
			persistence.close();
			IllegalStateException exception = Assert.assertThrows(IllegalStateException.class, () -> persistence.rewriteAsync(new RedisCoreImpl()));
			Assert.assertEquals("AOF持久化协调器已经关闭", exception.getMessage());
		} finally {
			persistence.close();
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证Rewrite使用当前内存数据替换旧AOF历史。
	 *
	 * @throws Exception AOF写入、Rewrite、重放或清理失败时抛出
	 */
	@Test(timeout = 10_000L)
	public void testRewriteReplacesOldHistoryWithMemorySnapshot() throws Exception {
		Path path = Files.createTempFile("miniredis-rewrite-snapshot-", ".aof");

		try {
			writeAof(path, command("SET", "stale", "old-value"));
			RedisCoreImpl redisCore = new RedisCoreImpl();
			redisCore.put(bytes("name"), new RedisString(bytes("李新数据")));

			try (AofPersistence persistence = new AofPersistence(path, AofFsyncPolicy.ALWAYS)) {
				Assert.assertTrue(persistence.rewriteAsync(redisCore));
				RedisCoreImpl restored = awaitStringValue(path, "name", "李新数据");
				Assert.assertNull(restored.get(bytes("stale")));
			}
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证已有Rewrite任务运行时不会重复提交新任务。
	 *
	 * @throws Exception AOF创建、同步等待或清理失败时抛出
	 */
	@Test(timeout = 10_000L)
	public void testRewriteRejectsDuplicateRequest() throws Exception {
		Path path = Files.createTempFile("miniredis-rewrite-duplicate-", ".aof");
		BlockingRedisCore redisCore = new BlockingRedisCore();
		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));
		AofPersistence persistence = new AofPersistence(path, AofFsyncPolicy.ALWAYS);

		try {
			Assert.assertTrue(persistence.rewriteAsync(redisCore));
			Assert.assertTrue(redisCore.awaitScan(2L, TimeUnit.SECONDS));
			Assert.assertFalse(persistence.rewriteAsync(redisCore));
			redisCore.releaseScan();
			awaitStringValue(path, "name", "twopair");
		} finally {
			redisCore.releaseScan();
			persistence.close();
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证Rewrite进行期间的新写命令会被保留在新AOF中。
	 *
	 * @throws Exception AOF创建、并发写入、重放或清理失败时抛出
	 */
	@Test(timeout = 10_000L)
	public void testRewriteKeepsIncrementalWrite() throws Exception {
		Path path = Files.createTempFile("miniredis-rewrite-incremental-", ".aof");
		BlockingRedisCore redisCore = new BlockingRedisCore();
		for (int i = 0; i < 1024; i++) {
			redisCore.put(bytes("snapshot-" + i), new RedisString(bytes("value-" + i)));
		}
		AofPersistence persistence = new AofPersistence(path, AofFsyncPolicy.ALWAYS);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		CountDownLatch writerStarted = new CountDownLatch(1);

		try {
			Assert.assertTrue(persistence.rewriteAsync(redisCore));
			Assert.assertTrue(redisCore.awaitScan(2L, TimeUnit.SECONDS));

			Thread writer = new Thread(() -> {
				writerStarted.countDown();
				try {
					synchronized (persistence) {
						redisCore.put(bytes("during-rewrite"), new RedisString(bytes("增量命令")));
						persistence.appendAll(List.of(command("SET", "during-rewrite", "增量命令")));
					}
				} catch (Throwable throwable) {
					writerFailure.set(throwable);
				}
			}, "aof-rewrite-test-writer");
			writer.start();
			Assert.assertTrue(writerStarted.await(2L, TimeUnit.SECONDS));
			redisCore.releaseScan();
			writer.join(5_000L);

			Assert.assertFalse(writer.isAlive());
			Assert.assertNull(writerFailure.get());
			// 旧AOF已经包含增量命令，必须等待快照key出现才能证明原子切换完成。
			RedisCoreImpl restored = awaitStringValue(path, "snapshot-1023", "value-1023");
			assertStringValue(restored, "during-rewrite", "增量命令");
		} finally {
			redisCore.releaseScan();
			persistence.close();
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证ALWAYS策略在每批命令追加后立即执行刷盘。
	 *
	 * @throws Exception 当AOF追加、刷盘或关闭失败时抛出
	 */
	@Test
	public void testAlwaysForcesImmediatelyAfterAppend() throws Exception {
		RecordingAofStorage storage = new RecordingAofStorage();

		try (AofPersistence persistence = new AofPersistence(
				storage,
				AofFsyncPolicy.ALWAYS,
				1000L
		)) {
			persistence.appendAll(List.of(command("SET", "name", "twopair")));

			Assert.assertEquals(
					List.of("append", "force"),
					storage.operationsSnapshot()
			);
		}
	}

	/**
	 * 验证EVERYSEC策略不在请求线程立即刷盘，而是交给独立守护线程。
	 *
	 * @throws Exception 当AOF追加、刷盘或等待失败时抛出
	 */
	@Test
	public void testEverysecForcesOnBackgroundDaemonThread() throws Exception {
		RecordingAofStorage storage = new RecordingAofStorage();
		AofPersistence persistence = new AofPersistence(
				storage,
				AofFsyncPolicy.EVERYSEC,
				200L
		);

		try {
			persistence.appendAll(List.of(command("SET", "city", "杭州")));

			// EVERYSEC不应在客户端请求线程中立即force。
			Assert.assertEquals(0, storage.forceCount());
			Assert.assertTrue(storage.awaitForce(2L, TimeUnit.SECONDS));

			Thread forceThread = storage.forceThread();
			Assert.assertNotNull(forceThread);
			Assert.assertTrue(forceThread.getName().startsWith("redis-aof-fsync"));
			Assert.assertTrue(forceThread.isDaemon());
		} finally {
			persistence.close();
		}
	}

	/**
	 * 验证关闭时会刷入EVERYSEC尚未同步的数据，并且重复关闭不会重复操作底层文件。
	 *
	 * @throws Exception 当AOF追加或关闭失败时抛出
	 */
	@Test
	public void testCloseForcesPendingDataAndIsIdempotent() throws Exception {
		RecordingAofStorage storage = new RecordingAofStorage();
		AofPersistence persistence = new AofPersistence(
				storage,
				AofFsyncPolicy.EVERYSEC,
				60_000L
		);

		persistence.appendAll(List.of(command("SET", "name", "twopair")));
		persistence.close();
		persistence.close();

		Assert.assertEquals(
				List.of("append", "force", "close"),
				storage.operationsSnapshot()
		);
		Assert.assertEquals(1, storage.closeCount());
	}

	/**
	 * 验证EVERYSEC在没有新数据时不会执行无意义的刷盘。
	 *
	 * @throws Exception 当等待或关闭失败时抛出
	 */
	@Test
	public void testEverysecSkipsForceWithoutNewWrites() throws Exception {
		RecordingAofStorage storage = new RecordingAofStorage();
		AofPersistence persistence = new AofPersistence(storage, AofFsyncPolicy.EVERYSEC, 50L);

		try {
			Assert.assertFalse(storage.awaitForce(200L, TimeUnit.MILLISECONDS));
		} finally {
			persistence.close();
		}

		Assert.assertEquals(List.of("close"), storage.operationsSnapshot());
	}

	/**
	 * 向指定AOF文件写入一条命令并立即刷盘。
	 *
	 * @param path AOF文件路径
	 * @param command 需要写入的命令
	 * @throws IOException AOF写入或刷盘失败时抛出
	 */
	private void writeAof(Path path, RespArray command) throws IOException {
		try (AofFile file = new AofFile(path)) {
			file.append(command);
			file.force();
		}
	}

	/**
	 * 等待Rewrite文件可以恢复出预期字符串。
	 *
	 * @param path AOF文件路径
	 * @param key 需要检查的key
	 * @param expected 期望恢复的字符串
	 * @return 成功恢复的Redis内存数据库
	 * @throws Exception AOF重放或等待失败时抛出
	 */
	private RedisCoreImpl awaitStringValue(Path path, String key, String expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
		Throwable lastFailure = null;

		while (System.nanoTime() < deadline) {
			try {
				RedisCoreImpl restored = new RedisCoreImpl();
				AofReplay.replay(path, restored);
				RedisData data = restored.get(bytes(key));
				if (data instanceof RedisString redisString && expected.equals(redisString.getValue().toUtf8String())) {
					return restored;
				}
			} catch (IOException | RuntimeException exception) {
				lastFailure = exception;
			}
			Thread.sleep(10L);
		}

		AssertionError error = new AssertionError("等待AOF Rewrite完成超时: key=" + key + ", expected=" + expected);
		if (lastFailure != null) {
			error.initCause(lastFailure);
		}
		throw error;
	}

	/**
	 * 断言Redis内存数据库中的字符串值。
	 *
	 * @param redisCore Redis内存数据库
	 * @param key 需要检查的key
	 * @param expected 期望的字符串
	 */
	private void assertStringValue(RedisCoreImpl redisCore, String key, String expected) {
		RedisData data = redisCore.get(bytes(key));
		Assert.assertTrue(data instanceof RedisString);
		Assert.assertEquals(expected, ((RedisString) data).getValue().toUtf8String());
	}

	/**
	 * 将字符串转换为项目统一的字节包装对象。
	 *
	 * @param value 字符串内容
	 * @return UTF-8字节包装对象
	 */
	private static BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 将字符串参数构造成redis-cli发送的RESP数组。
	 *
	 * @param arguments 命令名称和参数
	 * @return RESP数组命令
	 */
	private RespArray command(String... arguments) {
		Resp[] array = new Resp[arguments.length];

		for (int i = 0; i < arguments.length; i++) {
			array[i] = new BulkString(new BytesWrapper(
					arguments[i].getBytes(StandardCharsets.UTF_8)
			));
		}

		return new RespArray(array);
	}

	/**
	 * 在扫描key时可控阻塞的RedisCore测试替身。
	 */
	private static final class BlockingRedisCore extends RedisCoreImpl {
		private final CountDownLatch scanStarted = new CountDownLatch(1);
		private final CountDownLatch continueScan = new CountDownLatch(1);

		/**
		 * 通知测试线程已开始扫描，并等待允许继续。
		 *
		 * @return 当前所有未过期key的快照
		 * @throws IllegalStateException 等待线程被中断时抛出
		 */
		@Override
		public List<BytesWrapper> scanKeys() {
			scanStarted.countDown();
			try {
				continueScan.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("等待继续扫描时被中断", exception);
			}
			return super.scanKeys();
		}

		/**
		 * 等待Rewrite开始扫描key。
		 *
		 * @param timeout 最长等待时间
		 * @param unit 时间单位
		 * @return 超时前开始扫描时返回 {@code true}
		 * @throws InterruptedException 等待线程被中断时抛出
		 */
		private boolean awaitScan(long timeout, TimeUnit unit) throws InterruptedException {
			return scanStarted.await(timeout, unit);
		}

		/**
		 * 允许Rewrite继续扫描key。
		 */
		private void releaseScan() {
			continueScan.countDown();
		}
	}

	/**
	 * 记录持久化操作顺序的测试替身，避免测试依赖真实磁盘刷盘时机。
	 */
	private static final class RecordingAofStorage implements AofStorage {
		private final List<String> operations = new CopyOnWriteArrayList<>();
		private final AtomicInteger forceCount = new AtomicInteger();
		private final AtomicInteger closeCount = new AtomicInteger();
		private final AtomicReference<Thread> forceThread = new AtomicReference<>();
		private final CountDownLatch forceExecuted = new CountDownLatch(1);

		/**
		 * 记录一次AOF追加操作。
		 *
		 * @param commands 本次追加的命令
		 */
		@Override
		public void appendAll(List<RespArray> commands) {
			operations.add("append");
		}

		/**
		 * 记录刷盘线程与刷盘次数。
		 */
		@Override
		public void force() {
			forceThread.compareAndSet(null, Thread.currentThread());
			forceCount.incrementAndGet();
			operations.add("force");
			forceExecuted.countDown();
		}

		/**
		 * 记录底层存储关闭次数。
		 *
		 * @throws IOException 当前测试替身不会抛出该异常
		 */
		@Override
		public void close() throws IOException {
			closeCount.incrementAndGet();
			operations.add("close");
		}

		/**
		 * 获取当前记录的操作顺序快照。
		 *
		 * @return 操作顺序快照
		 */
		private List<String> operationsSnapshot() {
			return List.copyOf(operations);
		}

		/**
		 * 获取刷盘次数。
		 *
		 * @return 刷盘次数
		 */
		private int forceCount() {
			return forceCount.get();
		}

		/**
		 * 获取关闭次数。
		 *
		 * @return 关闭次数
		 */
		private int closeCount() {
			return closeCount.get();
		}

		/**
		 * 获取首次执行刷盘的线程。
		 *
		 * @return 首次执行刷盘的线程
		 */
		private Thread forceThread() {
			return forceThread.get();
		}

		/**
		 * 等待首次刷盘执行。
		 *
		 * @param timeout 最长等待时间
		 * @param unit 时间单位
		 * @return 在超时前执行刷盘返回true，否则返回false
		 * @throws InterruptedException 等待线程被中断时抛出
		 */
		private boolean awaitForce(long timeout, TimeUnit unit) throws InterruptedException {
			return forceExecuted.await(timeout, unit);
		}
	}
}
