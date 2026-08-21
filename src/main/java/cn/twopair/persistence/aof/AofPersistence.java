package cn.twopair.persistence.aof;

import cn.twopair.core.RedisCore;
import cn.twopair.resp.RespArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * 根据刷盘策略协调AOF追加、后台刷盘和关闭生命周期。
 *
 * @author ljj
 */
public final class AofPersistence implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AofPersistence.class);
	private static final long DEFAULT_FSYNC_INTERVAL_MILLIS = 1000L;

	/**
	 * 正式AOF文件路径；测试替身没有真实文件时为 {@code null}。
	 */
	private final Path path;

	/**
	 * 当前正在接收追加命令的AOF存储。
	 *
	 * <p>Rewrite成功后需要关闭旧存储并切换到新文件，因此不能声明为final。
	 */
	private AofStorage storage;

	/**
	 * 单线程执行AOF Rewrite磁盘操作，避免阻塞Netty EventLoop。
	 */
	private final ExecutorService rewriteExecutor;

	/**
	 * 标记当前是否正在执行AOF Rewrite。
	 */
	private volatile boolean rewriteInProgress;

	/**
	 * 保存内存快照完成后产生的增量写命令。
	 */
	private List<RespArray> rewriteBuffer = new ArrayList<>();

	private final AofFsyncPolicy policy;
	private final ScheduledExecutorService fsyncExecutor;
	private final ScheduledFuture<?> fsyncTask;

	private boolean closed;
	private boolean dirty;
	private IOException backgroundFailure;

	/**
	 * 使用指定文件和刷盘策略创建AOF持久化协调器。
	 *
	 * @param path   AOF文件路径
	 * @param policy AOF刷盘策略
	 * @throws NullPointerException path或policy为 {@code null} 时抛出
	 * @throws IOException AOF文件打开失败时抛出
	 */
	public AofPersistence(Path path, AofFsyncPolicy policy) throws IOException {
		this(openStorage(path, policy), path.toAbsolutePath(), policy, DEFAULT_FSYNC_INTERVAL_MILLIS);
	}

	/**
	 * 使用测试替身创建AOF持久化协调器。
	 *
	 * <p>测试替身没有真实文件路径，因此不能执行AOF Rewrite。
	 *
	 * @param storage             AOF底层存储
	 * @param policy              AOF刷盘策略
	 * @param fsyncIntervalMillis 后台刷盘间隔，单位为毫秒
	 * @throws NullPointerException     storage或policy为 {@code null} 时抛出
	 * @throws IllegalArgumentException 刷盘间隔小于等于0时抛出
	 */
	AofPersistence(AofStorage storage, AofFsyncPolicy policy, long fsyncIntervalMillis) {
		this(storage, null, policy, fsyncIntervalMillis);
	}

	/**
	 * 使用完整配置创建AOF持久化协调器。
	 *
	 * @param storage AOF底层存储
	 * @param path 正式AOF文件路径；测试替身可以为 {@code null}
	 * @param policy AOF刷盘策略
	 * @param fsyncIntervalMillis 后台刷盘间隔，单位为毫秒
	 * @throws NullPointerException storage或policy为 {@code null} 时抛出
	 * @throws IllegalArgumentException 刷盘间隔小于等于0时抛出
	 */
	private AofPersistence(AofStorage storage, Path path, AofFsyncPolicy policy, long fsyncIntervalMillis) {
		if (fsyncIntervalMillis <= 0L) {
			throw new IllegalArgumentException("AOF刷盘间隔必须大于0");
		}

		this.path = path;
		this.storage = Objects.requireNonNull(storage, "AOF存储不能为空");
		this.policy = Objects.requireNonNull(policy, "AOF刷盘策略不能为空");
		this.rewriteExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "redis-aof-rewrite");
			thread.setDaemon(true);
			return thread;
		});

		if (policy == AofFsyncPolicy.EVERYSEC) {
			fsyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "redis-aof-fsync");
				thread.setDaemon(true);
				return thread;
			});
			fsyncTask = fsyncExecutor.scheduleWithFixedDelay(this::runBackgroundForce, fsyncIntervalMillis, fsyncIntervalMillis, TimeUnit.MILLISECONDS);
		} else {
			fsyncExecutor = null;
			fsyncTask = null;
		}
	}

	/**
	 * 按照当前刷盘策略追加一批AOF命令。
	 *
	 * <p>ALWAYS策略在追加后立即刷盘；EVERYSEC策略只追加，
	 * 由后台任务定期执行刷盘。
	 *
	 * @param commands 需要追加的AOF命令
	 * @throws NullPointerException     commands为 {@code null} 时抛出
	 * @throws IOException           文件写入、立即刷盘或后台刷盘失败时抛出
	 * @throws IllegalStateException 协调器已经关闭时抛出
	 */
	public synchronized void appendAll(List<RespArray> commands) throws IOException {
		if (closed) {
			throw new IllegalStateException("AOF持久化协调器已经关闭");
		}
		if (backgroundFailure != null) {
			throw new IOException("AOF后台刷盘已经失败", backgroundFailure);
		}

		Objects.requireNonNull(commands, "AOF命令列表不能为空");

		if (commands.isEmpty()) {
			return;
		}

		storage.appendAll(commands);
		if (rewriteInProgress) {
			rewriteBuffer.addAll(commands);
		}
		dirty = true;

		if (policy == AofFsyncPolicy.ALWAYS) {
			storage.force();
			dirty = false;
		}
	}

	/**
	 * 异步启动一次AOF Rewrite。
	 *
	 * <p>该方法只负责提交后台任务，不在调用线程执行文件写入。已经存在Rewrite任务时不会重复提交。
	 *
	 * @param redisCore Redis内存数据库
	 * @return 成功提交任务时返回 {@code true}，已有任务运行时返回 {@code false}
	 * @throws NullPointerException  redisCore为 {@code null} 时抛出
	 * @throws IllegalStateException 协调器已关闭、没有真实AOF路径或后台任务无法提交时抛出
	 */
	public boolean rewriteAsync(RedisCore redisCore) {
		Objects.requireNonNull(redisCore, "RedisCore不能为空");

		// volatile快速判断，避免第二次请求阻塞在正在建立快照的同步锁上。
		if (rewriteInProgress) {
			return false;
		}

		synchronized (this) {
			if (closed) {
				throw new IllegalStateException("AOF持久化协调器已经关闭");
			}
			if (path == null) {
				throw new IllegalStateException("当前AOF存储没有真实文件路径");
			}
			if (rewriteInProgress) {
				return false;
			}

			rewriteInProgress = true;
			rewriteBuffer = new ArrayList<>();

			try {
				rewriteExecutor.execute(() -> runRewrite(redisCore));
				return true;
			} catch (RejectedExecutionException exception) {
				rewriteInProgress = false;
				throw new IllegalStateException("AOF Rewrite任务提交失败", exception);
			}
		}
	}

	/**
	 * 在后台线程生成并写入新的AOF文件。
	 *
	 * @param redisCore Redis内存数据库
	 */
	private void runRewrite(RedisCore redisCore) {
		try (AofRewriteFile rewriteFile = new AofRewriteFile(path)) {
			List<RespArray> snapshotCommands;

			synchronized (this) {
				if (closed) {
					return;
				}

				snapshotCommands = AofRewriteCommandBuilder.build(redisCore);

				// 建立快照之前产生的写命令已经包含在快照中，不能再次重放。
				rewriteBuffer.clear();
			}

			rewriteFile.appendAll(snapshotCommands);
			finishRewrite(rewriteFile);
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("AOF Rewrite失败", exception);
		} finally {
			synchronized (this) {
				rewriteInProgress = false;
				rewriteBuffer = new ArrayList<>();
			}
		}
	}

	/**
	 * 将Rewrite期间产生的增量命令追加到临时文件，并切换正式AOF文件。
	 *
	 * @param rewriteFile 已经写入内存快照的Rewrite临时文件
	 * @throws IOException 增量写入、文件替换或新AOF文件打开失败时抛出
	 */
	private synchronized void finishRewrite(AofRewriteFile rewriteFile) throws IOException {
		if (closed) {
			return;
		}

		rewriteFile.appendAll(rewriteBuffer);
		rewriteFile.commit();

		AofStorage replacement;
		try {
			replacement = new AofFile(path);
		} catch (IOException exception) {
			backgroundFailure = exception;
			throw exception;
		}

		AofStorage previous = storage;
		storage = replacement;
		dirty = false;

		try {
			previous.close();
		} catch (IOException exception) {
			LOGGER.warn("旧AOF文件关闭失败", exception);
		}
	}

	/**
	 * 执行一次EVERYSEC后台刷盘。
	 *
	 * <p>后台异常必须在这里捕获并保存，否则周期任务抛出异常后，
	 * ScheduledExecutorService将停止后续调度。
	 */
	private synchronized void runBackgroundForce() {
		if (closed || !dirty) {
			return;
		}

		try {
			storage.force();
			dirty = false;
		} catch (IOException exception) {
			backgroundFailure = exception;
			LOGGER.error("AOF后台刷盘失败", exception);
		}
	}

	/**
	 * 关闭AOF持久化协调器及其后台线程。
	 *
	 * <p>关闭前会执行最后一次刷盘，确保EVERYSEC尚未同步的数据
	 * 尽可能写入磁盘。重复调用不会重复关闭底层资源。
	 *
	 * @throws IOException 最终刷盘或底层存储关闭失败时抛出
	 */
	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		rewriteExecutor.shutdownNow();

		if (fsyncTask != null) {
			fsyncTask.cancel(false);
		}
		if (fsyncExecutor != null) {
			fsyncExecutor.shutdown();
		}

		IOException failure = null;

		if (dirty) {
			try {
				storage.force();
				dirty = false;
			} catch (IOException exception) {
				failure = exception;
			}
		}

		try {
			storage.close();
		} catch (IOException exception) {
			if (failure == null) {
				failure = exception;
			} else {
				failure.addSuppressed(exception);
			}
		}

		if (failure != null) {
			throw failure;
		}
	}

	/**
	 * 校验刷盘策略并打开指定的AOF文件。
	 *
	 * @param path   AOF文件路径
	 * @param policy AOF刷盘策略
	 * @return 已打开的AOF底层存储
	 * @throws NullPointerException path或policy为 {@code null} 时抛出
	 * @throws IOException AOF文件打开失败时抛出
	 */
	private static AofStorage openStorage(Path path, AofFsyncPolicy policy) throws IOException {
		Objects.requireNonNull(policy, "AOF刷盘策略不能为空");
		return new AofFile(path);
	}
}
