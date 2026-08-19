package cn.twopair;

import cn.twopair.server.RedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author ljj
 * @description MiniRedis 应用启动入口。
 * @date 2026/7/16
 * @twopair
 */
public class Main {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		RedisServer server = new RedisServer();
		LOGGER.info("MiniRedis应用开始启动");

		// JVM 退出或按下 Ctrl+C 时释放端口和 Netty 线程。
		Runtime.getRuntime().addShutdownHook(
				new Thread(server::stop, "mini-redis-shutdown")
		);

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
}
