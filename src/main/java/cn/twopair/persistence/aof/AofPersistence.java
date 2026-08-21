package cn.twopair.persistence.aof;

import cn.twopair.resp.RespArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author ljj
 * @description 根据刷盘策略协调AOF追加、后台刷盘和关闭生命周期。
 * @date 2026/8/20
 * @twopair
 */
public final class AofPersistence implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AofPersistence.class);
	private static final long DEFAULT_FSYNC_INTERVAL_MILLIS = 1000L;

	private final AofStorage storage;
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
	 * @throws IOException AOF文件打开失败时抛出
	 */
	public AofPersistence(Path path, AofFsyncPolicy policy) throws IOException {
		this(openStorage(path, policy), policy, DEFAULT_FSYNC_INTERVAL_MILLIS);
	}

	/**
	 * 使用指定底层存储、刷盘策略和间隔创建协调器。
	 *
	 * <p>该构造方法保留包级可见性，方便测试替换底层存储，
	 * 避免测试依赖真实磁盘的刷盘时机。
	 *
	 * @param storage             AOF底层存储
	 * @param policy              AOF刷盘策略
	 * @param fsyncIntervalMillis 后台刷盘间隔，单位为毫秒
	 * @throws IllegalArgumentException 刷盘间隔小于等于0时抛出
	 */
	AofPersistence(AofStorage storage, AofFsyncPolicy policy, long fsyncIntervalMillis) {
		if (fsyncIntervalMillis <= 0L) {
			throw new IllegalArgumentException("AOF刷盘间隔必须大于0");
		}

		this.storage = Objects.requireNonNull(storage, "AOF存储不能为空");
		this.policy = Objects.requireNonNull(policy, "AOF刷盘策略不能为空");

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
		dirty = true;

		if (policy == AofFsyncPolicy.ALWAYS) {
			storage.force();
			dirty = false;
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
	 * @throws IOException AOF文件打开失败时抛出
	 */
	private static AofStorage openStorage(Path path, AofFsyncPolicy policy) throws IOException {
		Objects.requireNonNull(policy, "AOF刷盘策略不能为空");
		return new AofFile(path);
	}
}
