package cn.twopair.server;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author ljj
 * @description Redis Netty 服务生命周期测试。
 * @date 2026/7/15
 * @twopair
 */
public class RedisServerTest {

	/**
	 * @author ljj
	 * @description 测试服务绑定随机端口并安全停止。
	 * @date 2026/7/15
	 * @twopair
	 */
	@Test
	public void testStartOnRandomPortAndStop() {
		RedisServer server = new RedisServer(0);

		try {
			// start() 返回时必须已经完成端口绑定。
			server.start();
			Assert.assertTrue(server.getPort() > 0);
		} finally {
			// 即使测试失败，也必须释放 Netty 线程和端口。
			server.stop();
		}
	}

	/**
	 * @author ljj
	 * @description 测试客户端通过真实 TCP 连接执行 PING。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testHandlePingOverTcp() throws Exception {
		RedisServer server = new RedisServer(0);

		try {
			server.start();

			try (Socket client = new Socket("127.0.0.1", server.getPort())) {
				// 防止服务端没有响应时测试永久阻塞。
				client.setSoTimeout(2000);

				OutputStream output = client.getOutputStream();
				BufferedReader input = new BufferedReader(
						new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
				);

				// redis-cli 的 PING 会被编码成这个 RESP Array。
				output.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
				output.flush();

				// readLine() 会移除响应末尾的 CRLF。
				Assert.assertEquals("+PONG", input.readLine());
			}
		} finally {
			server.stop();
		}
	}

	/**
	 * @author ljj
	 * @description 测试不同 TCP 连接共享同一个 RedisCore。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testShareDataBetweenConnections() throws IOException {
		RedisServer server = new RedisServer(0);

		try {
			server.start();

			try (
					Socket firstClient =
							new Socket("127.0.0.1", server.getPort());
					Socket secondClient =
							new Socket("127.0.0.1", server.getPort())
			) {
				//// 防止服务端异常时测试永久等待。
				firstClient.setSoTimeout(2000);
				secondClient.setSoTimeout(2000);

				OutputStream firstOutput = firstClient.getOutputStream();
				BufferedReader firstInput = new BufferedReader(
						new InputStreamReader(
								firstClient.getInputStream(),
								StandardCharsets.UTF_8
						)
				);

				OutputStream secondOutput = secondClient.getOutputStream();
				BufferedReader secondInput = new BufferedReader(
						new InputStreamReader(
								secondClient.getInputStream(),
								StandardCharsets.UTF_8
						)
				);

				// 第一个客户端执行 SET name twopair。
				firstOutput.write((
						"*3\r\n"
								+ "$3\r\nSET\r\n"
								+ "$4\r\nname\r\n"
								+ "$7\r\ntwopair\r\n"
				).getBytes(StandardCharsets.UTF_8));
				firstOutput.flush();

				Assert.assertEquals("+OK", firstInput.readLine());

				// 第二个客户端读取第一个客户端写入的数据。
				secondOutput.write((
						"*2\r\n"
								+ "$3\r\nGET\r\n"
								+ "$4\r\nname\r\n"
				).getBytes(StandardCharsets.UTF_8));
				secondOutput.flush();

				// BulkString 响应包含长度行和内容行。
				Assert.assertEquals("$7", secondInput.readLine());
				Assert.assertEquals("twopair", secondInput.readLine());
			}
		} finally {
			server.stop();
		}
	}

	/**
	 * @author ljj
	 * @description 测试服务关闭前持续阻塞，关闭后结束等待，并支持重复关闭。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testBlockUntilShutdown() throws InterruptedException {
		Thread waitingThread = null;

		try (RedisServer server = new RedisServer(0)) {
			server.start();

			/*
			 * 用于确认等待线程已经启动，
			 * 避免主线程过早进行断言。
			 */
			CountDownLatch threadStarted = new CountDownLatch(1);

			waitingThread = new Thread(() -> {
				threadStarted.countDown();
				server.blockUntilShutdown();
			}, "redis-server-wait-test");

			waitingThread.start();

			// 确认等待线程已经开始执行。
			Assert.assertTrue(
					threadStarted.await(1, TimeUnit.SECONDS)
			);

			// 给线程少量时间进入 closeFuture().sync()。
			Thread.sleep(100);

			// 服务尚未关闭，blockUntilShutdown() 应继续阻塞。
			Assert.assertTrue(waitingThread.isAlive());

			// 第一次关闭会完成 serverChannel.closeFuture()。
			server.stop();

			// 最多等待一秒，确认阻塞线程已经退出。
			waitingThread.join(1000);
			Assert.assertFalse(waitingThread.isAlive());

			/*
			 * 离开 try 时会自动调用 server.close()。
			 * 因为 stop() 已经执行过，AtomicBoolean 会让 close() 直接返回。
			 */
		} finally {
			// 如果测试中途失败，确保辅助线程不会残留。
			if (waitingThread != null && waitingThread.isAlive()) {
				waitingThread.interrupt();
				waitingThread.join(1000);
			}
		}
	}
}