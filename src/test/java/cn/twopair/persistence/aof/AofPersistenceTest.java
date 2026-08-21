package cn.twopair.persistence.aof;

import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author ljj
 * @description 测试AOF持久化协调器的刷盘策略与关闭语义。
 * @date 2026/8/20
 * @twopair
 */
public class AofPersistenceTest {

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
