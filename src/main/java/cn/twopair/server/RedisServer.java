package cn.twopair.server;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.server.codec.RespDecoder;
import cn.twopair.server.codec.RespEncoder;
import cn.twopair.server.handler.CommandHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author ljj
 * @description MiniRedis Netty 服务，负责 TCP 监听、Pipeline 组装和生命周期管理。
 * @date 2026/7/15
 * @twopair
 */
public class RedisServer implements AutoCloseable {
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


	/**
	 * 使用默认端口和默认清理间隔创建Redis服务。
	 */
	public RedisServer() {
		this(DEFAULT_PORT);
	}

	/**
	 * 使用指定端口创建Redis服务。
	 *
	 * @param port 服务监听端口，传入0时由操作系统分配端口
	 */
	public RedisServer(int port) {
		this(
				port,
				new RedisCoreImpl(),
				DEFAULT_CLEANUP_INTERVAL_MILLIS
		);
	}

	/**
	 * 使用指定核心存储和清理间隔创建Redis服务。
	 *
	 * @param port                  服务监听端口
	 * @param redisCore             Redis核心存储
	 * @param cleanupIntervalMillis 主动清理间隔，单位为毫秒
	 * @throws IllegalArgumentException 当清理间隔小于等于0时抛出
	 */
	RedisServer(
			int port,
			RedisCore redisCore,
			long cleanupIntervalMillis
	) {
		if (cleanupIntervalMillis <= 0L) {
			throw new IllegalArgumentException("主动清理间隔必须大于0");
		}

		this.port = port;
		this.redisCore = Objects.requireNonNull(
				redisCore,
				"RedisCore不能为空"
		);
		this.cleanupIntervalMillis = cleanupIntervalMillis;

		this.expirationExecutor =
				Executors.newSingleThreadScheduledExecutor(runnable -> {
					Thread thread = new Thread(
							runnable,
							"redis-expiration-cleaner"
					);

					// 守护线程不会阻止JVM正常退出。
					thread.setDaemon(true);
					return thread;
				});
	}

	public int getPort() {
		if (serverChannel == null) {
			throw new IllegalStateException("Redis服务尚未启动");
		}
		// 传入 0 时，从本地地址中取得操作系统实际分配的端口。
		return ((InetSocketAddress) serverChannel.localAddress()).getPort();
	}

	public void start() {
		try {
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
						@Override
						protected void initChannel(SocketChannel channel) {
							/*
							 * 入站：RespDecoder -> CommandHandler。
							 * 出站由 CommandHandler 向前传播：CommandHandler -> RespEncoder。
							 */
							channel.pipeline()
									.addLast(new RespDecoder())
									.addLast(new RespEncoder())
									.addLast(new CommandHandler(redisCore));
						}
					});

			// bind() 异步绑定；sync() 等待绑定完成后再返回。
			serverChannel = bootstrap.bind(port).sync().channel();
			/*
			 * 使用固定延迟：本次清理结束后，再等待指定时间执行下一次。
			 * 避免清理速度跟不上时产生任务堆积。
			 */
			expirationCleanupTask = expirationExecutor.scheduleWithFixedDelay(
					redisCore::removeExpired,
					cleanupIntervalMillis,
					cleanupIntervalMillis,
					TimeUnit.MILLISECONDS
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			stop();
			throw new IllegalStateException("Redis服务启动被中断", e);
		} catch (RuntimeException e) {
			stop();
			throw e;
		}
	}

	/**
	 * @author ljj
	 * @description 保留符合服务语义的停止方法。
	 * @date 2026/7/16
	 * @twopair
	 */
	public void stop() {
		close();
	}

	/**
	 * @author ljj
	 * @description 幂等关闭服务端 Channel 和 Netty 线程组。
	 * @date 2026/7/16
	 * @twopair
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
	}


	/**
	 * @author ljj
	 * @description 阻塞当前调用线程，直到服务端 Channel 被关闭。
	 * @date 2026/7/16
	 * @twopair
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
		}
	}
}
