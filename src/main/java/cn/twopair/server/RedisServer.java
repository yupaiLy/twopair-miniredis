package cn.twopair.server;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.persistence.aof.AofFsyncPolicy;
import cn.twopair.persistence.aof.AofPersistence;
import cn.twopair.persistence.aof.AofReplay;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiniRedis Netty 服务，负责 TCP 监听、Pipeline 组装和生命周期管理。
 *
 * @author ljj
 */
public class RedisServer implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisServer.class);
	/** MiniRedis默认监听端口。 */
	public static final int DEFAULT_PORT = 6378;
	private static final long DEFAULT_CLEANUP_INTERVAL_MILLIS = 1000L;

	private final int port;
	// 所有客户端连接必须共享同一个 Redis 数据存储。
	private final RedisCore redisCore;
	/*
	 * compareAndSet(false, true) 只有一个线程能成功，
	 * 保证 shutdown hook、finally、测试重复关闭时只有一次真正执行。
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;


	private final long cleanupIntervalMillis;

	/**
	 * 专门执行过期清理，避免耗时扫描阻塞Netty EventLoop。
	 */
	private final ScheduledExecutorService expirationExecutor;

	private ScheduledFuture<?> expirationCleanupTask;

	/** 默认AOF文件路径。 */
	public static final Path DEFAULT_AOF_PATH = Path.of("data", "appendonly.aof");
	/** 默认AOF刷盘策略。 */
	public static final AofFsyncPolicy DEFAULT_AOF_FSYNC_POLICY = AofFsyncPolicy.ALWAYS;

	private final Path aofPath;
	private final AofFsyncPolicy aofFsyncPolicy;
	private AofPersistence aofPersistence;

	/**
	 * 使用默认端口、默认AOF路径和默认刷盘策略创建服务。
	 */
	public RedisServer() {
		this(DEFAULT_PORT, DEFAULT_AOF_PATH, DEFAULT_AOF_FSYNC_POLICY);
	}

	/**
	 * 创建未启用AOF的Redis服务。
	 *
	 * @param port 服务监听端口
	 */
	public RedisServer(int port) {
		this(port, new RedisCoreImpl(), DEFAULT_CLEANUP_INTERVAL_MILLIS, null, DEFAULT_AOF_FSYNC_POLICY);
	}

	/**
	 * 创建启用AOF持久化的Redis服务。
	 *
	 * @param port    服务监听端口
	 * @param aofPath AOF文件路径
	 */
	public RedisServer(int port, Path aofPath) {
		this(port, aofPath, DEFAULT_AOF_FSYNC_POLICY);
	}

	/**
	 * 使用指定AOF路径和刷盘策略创建Redis服务。
	 *
	 * @param port           服务监听端口
	 * @param aofPath        AOF文件路径
	 * @param aofFsyncPolicy AOF刷盘策略
	 */
	public RedisServer(int port, Path aofPath, AofFsyncPolicy aofFsyncPolicy) {
		this(port, new RedisCoreImpl(), DEFAULT_CLEANUP_INTERVAL_MILLIS, Objects.requireNonNull(aofPath, "AOF文件路径不能为空"), aofFsyncPolicy);
	}

	/**
	 * 使用指定核心存储和过期清理间隔创建未启用AOF的服务。
	 *
	 * @param port                  服务监听端口
	 * @param redisCore             Redis核心存储
	 * @param cleanupIntervalMillis 过期清理间隔，单位为毫秒
	 */
	RedisServer(int port, RedisCore redisCore, long cleanupIntervalMillis) {
		this(port, redisCore, cleanupIntervalMillis, null, DEFAULT_AOF_FSYNC_POLICY);
	}

	/**
	 * 使用默认刷盘策略创建可配置内部依赖的Redis服务。
	 *
	 * @param port                  服务监听端口
	 * @param redisCore             Redis核心存储
	 * @param cleanupIntervalMillis 过期清理间隔，单位为毫秒
	 * @param aofPath               AOF文件路径；为 {@code null} 表示禁用AOF
	 */
	RedisServer(int port, RedisCore redisCore, long cleanupIntervalMillis, Path aofPath) {
		this(port, redisCore, cleanupIntervalMillis, aofPath, DEFAULT_AOF_FSYNC_POLICY);
	}

	/**
	 * 使用完整配置创建Redis服务。
	 *
	 * @param port                  服务监听端口
	 * @param redisCore             Redis核心存储
	 * @param cleanupIntervalMillis 过期清理间隔，单位为毫秒
	 * @param aofPath               AOF文件路径；为 {@code null} 表示禁用AOF
	 * @param aofFsyncPolicy        AOF刷盘策略
	 */
	RedisServer(int port, RedisCore redisCore, long cleanupIntervalMillis, Path aofPath, AofFsyncPolicy aofFsyncPolicy) {
		if (cleanupIntervalMillis <= 0L) {
			throw new IllegalArgumentException("主动清理间隔必须大于0");
		}

		this.port = port;
		this.redisCore = Objects.requireNonNull(redisCore, "RedisCore不能为空");
		this.cleanupIntervalMillis = cleanupIntervalMillis;
		this.aofPath = aofPath;
		this.aofFsyncPolicy = Objects.requireNonNull(aofFsyncPolicy, "AOF刷盘策略不能为空");

		this.expirationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "redis-expiration-cleaner");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * 获取服务器实际监听端口。
	 *
	 * @return 服务器实际监听端口
	 * @throws IllegalStateException 服务器尚未启动时抛出
	 */
	public int getPort() {
		if (serverChannel == null) {
			throw new IllegalStateException("Redis服务尚未启动");
		}
		// 传入 0 时，从本地地址中取得操作系统实际分配的端口。
		return ((InetSocketAddress) serverChannel.localAddress()).getPort();
	}

	/**
	 * 重放AOF并启动Netty服务、命令处理与过期清理任务。
	 *
	 * @throws IllegalStateException AOF初始化、端口绑定或启动过程失败时抛出
	 */
	public void start() {
		LOGGER.info(
				"Redis服务开始启动: requestedPort={}, aofEnabled={}, aofPath={}, aofFsyncPolicy={}, cleanupIntervalMs={}",
				port,
				aofPath != null,
				aofPath,
				aofFsyncPolicy,
				cleanupIntervalMillis
		);

		try {
			if (aofPath != null) {
				// 必须先恢复历史数据，防止客户端看到只恢复了一部分的数据。
				int replayedCount = AofReplay.replay(aofPath, redisCore);
				LOGGER.info("AOF恢复完成: path={}, replayedCommands={}", aofPath.toAbsolutePath(), replayedCount);

				// 重放完成后再打开文件，记录服务器运行期间的新写命令。
				aofPersistence = new AofPersistence(aofPath, aofFsyncPolicy);
			}
			// Boss 组使用一个线程，负责接收客户端连接。
			bossGroup = new MultiThreadIoEventLoopGroup(
					1,
					NioIoHandler.newFactory()
			);
			// Worker 组使用默认线程数量，负责连接的网络读写。
			workerGroup = new MultiThreadIoEventLoopGroup(
					NioIoHandler.newFactory()
			);
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						/**
						 * {@inheritDoc}
						 */
						@Override
						protected void initChannel(SocketChannel channel) {
							RedisPipeline.configure(channel.pipeline(), redisCore, aofPersistence);
						}
					});

			/*
			 * bind()异步绑定，await()只负责等待，不会把BindException以特殊方式重新抛出。
			 * 显式检查Future可以准确区分端口绑定失败和前面的AOF初始化失败。
			 */
			ChannelFuture bindFuture = bootstrap.bind(port);
			bindFuture.await();
			if (!bindFuture.isSuccess()) {
				throw new IllegalStateException("Redis服务端口绑定失败: port=" + port, bindFuture.cause());
			}
			serverChannel = bindFuture.channel();
			LOGGER.info("Redis服务启动完成: localAddress={}", serverChannel.localAddress());
			/*
			 * 使用固定延迟：本次清理结束后，再等待指定时间执行下一次。
			 * 避免清理速度跟不上时产生任务堆积。
			 */
			expirationCleanupTask = expirationExecutor.scheduleWithFixedDelay(
					this::runExpirationCleanup,
					cleanupIntervalMillis,
					cleanupIntervalMillis,
					TimeUnit.MILLISECONDS
			);
		} catch (IOException e) {
			LOGGER.error("Redis服务AOF初始化失败: path={}", aofPath, e);
			stop();
			throw new IllegalStateException("AOF初始化失败", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Redis服务启动被中断", e);
			stop();
			throw new IllegalStateException("Redis服务启动被中断", e);
		} catch (RuntimeException e) {
			LOGGER.error("Redis服务启动失败", e);
			stop();
			throw e;
		}
	}

	/**
	 * 执行一次主动过期清理并记录清理结果。
	 */
	private void runExpirationCleanup() {
		try {
			int removedCount = redisCore.removeExpired();
			if (removedCount > 0) {
				LOGGER.debug("主动过期清理完成: removedKeys={}", removedCount);
			}
		} catch (RuntimeException e) {
			// 定时任务抛出异常后将停止后续调度，因此必须在这里捕获并记录。
			LOGGER.error("主动过期清理失败", e);
		}
	}

	/**
	 * 停止服务，语义上等价于 {@link #close()}。
	 */
	public void stop() {
		close();
	}

	/**
	 * 幂等关闭服务端 Channel 和 Netty 线程组。
	 */
	@Override
	public void close() {
		/*
		 * 第一次调用将 false 改成 true 并继续执行；
		 * 后续调用无法再次修改，直接返回。
		 */
		if (!closed.compareAndSet(false, true)) {
			return;
		}

		LOGGER.info("Redis服务开始关闭: localAddress={}", serverChannel == null ? "未绑定" : serverChannel.localAddress());

		if (expirationCleanupTask != null) {
			// 不强制中断当前清理，但禁止后续调度。
			expirationCleanupTask.cancel(false);
		}
		// 释放RedisServer自己创建的后台线程。
		expirationExecutor.shutdownNow();

		if (serverChannel != null) {
			serverChannel.close().syncUninterruptibly();
		}

		// 同时发起关闭，避免两个 quiet period 串行等待。
		Future<?> bossFuture = bossGroup == null
				? null
				: bossGroup.shutdownGracefully(
				2, 12, TimeUnit.SECONDS
		);

		Future<?> workerFuture = workerGroup == null
				? null
				: workerGroup.shutdownGracefully(
				2, 12, TimeUnit.SECONDS
		);

		if (bossFuture != null) {
			bossFuture.syncUninterruptibly();
		}
		if (workerFuture != null) {
			workerFuture.syncUninterruptibly();
		}

		if (aofPersistence != null) {
			try {
				aofPersistence.close();
			} catch (IOException e) {
				LOGGER.error("AOF持久化协调器关闭失败: path={}", aofPath, e);
				throw new IllegalStateException("AOF持久化协调器关闭失败", e);
			}
		}

		LOGGER.info("Redis服务关闭完成");
	}


	/**
	 * 阻塞当前调用线程，直到服务端 Channel 被关闭。
	 */
	public void blockUntilShutdown() {
		if (serverChannel == null) {
			throw new IllegalStateException("Redis服务尚未启动");
		}

		try {
			/*
			 * 这里只阻塞调用它的 Main 线程。
			 * Boss 和 Worker 的 EventLoop 线程仍然正常处理网络事件。
			 */
			serverChannel.closeFuture().sync();
		} catch (InterruptedException e) {
			// 恢复中断标记，由 Main 的 finally 负责关闭服务。
			Thread.currentThread().interrupt();
			LOGGER.warn("等待Redis服务关闭时线程被中断");
		}
	}
}
