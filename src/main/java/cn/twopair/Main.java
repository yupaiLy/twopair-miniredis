package cn.twopair;

import cn.twopair.persistence.aof.AofFsyncPolicy;
import cn.twopair.server.RedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MiniRedis 应用启动入口。
 *
 * @author ljj
 */
public class Main {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
	/** AOF刷盘策略对应的JVM系统属性名称。 */
	public static final String AOF_FSYNC_PROPERTY = "miniredis.aof.fsync";

	/**
	 * 读取启动配置并运行MiniRedis服务。
	 *
	 * @param args 命令行参数，当前版本暂未使用
	 */
	public static void main(String[] args) {
		RedisServer server = createServer();
		LOGGER.info("MiniRedis应用开始启动");

		// JVM 退出或按下 Ctrl+C 时释放端口和 Netty 线程。
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "mini-redis-shutdown"));

		try {
			server.start();

			// 防止 Main 提前结束，持续等待服务关闭。
			server.blockUntilShutdown();
		} finally {
			// 启动或等待过程异常时，同样保证资源释放。
			server.stop();
			LOGGER.info("MiniRedis应用已退出");
		}
	}

	/**
	 * 根据外部配置创建Redis服务。
	 *
	 * @return 已完成配置但尚未启动的Redis服务
	 */
	static RedisServer createServer() {
		return new RedisServer(RedisServer.DEFAULT_PORT, RedisServer.DEFAULT_AOF_PATH, loadAofFsyncPolicy());
	}

	/**
	 * 从JVM系统属性读取AOF刷盘策略，未配置时保持原有ALWAYS策略。
	 *
	 * @return 当前启动使用的AOF刷盘策略
	 * @throws IllegalArgumentException 配置值不受支持时抛出
	 */
	static AofFsyncPolicy loadAofFsyncPolicy() {
		String configuredValue = System.getProperty(AOF_FSYNC_PROPERTY, RedisServer.DEFAULT_AOF_FSYNC_POLICY.name());
		return AofFsyncPolicy.parse(configuredValue);
	}
}
