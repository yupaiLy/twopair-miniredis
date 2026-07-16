package cn.twopair;

import cn.twopair.server.RedisServer;

/**
 * @author ljj
 * @description MiniRedis 应用启动入口。
 * @date 2026/7/16
 * @twopair
 */
public class Main {

	public static void main(String[] args) {
		RedisServer server = new RedisServer();

		// JVM 退出或按下 Ctrl+C 时释放端口和 Netty 线程。
		Runtime.getRuntime().addShutdownHook(
				new Thread(server::stop, "mini-redis-shutdown")
		);

		try {
			server.start();
			System.out.println("MiniRedis started at port " + server.getPort());

			// 防止 Main 提前结束，持续等待服务关闭。
			server.blockUntilShutdown();
		} finally {
			// 启动或等待过程异常时，同样保证资源释放。
			server.stop();
		}
	}
}