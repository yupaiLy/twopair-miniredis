package cn.twopair.server.handler;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.resp.SimpleString;
import cn.twopair.server.codec.RespDecoder;
import cn.twopair.server.codec.RespEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;


/**
 * @author ljj
 * @description Redis 命令业务处理器测试。
 * @date 2026/7/13
 * @twopair
 */
public class CommandHandlerTest {

	/**
	 * @author ljj
	 * @description 测试 PING 经过 CommandHandler 后返回 PONG。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Test
	public void testHandlePing() {
		// CommandHandler 执行命令时需要访问 Redis 核心存储。
		RedisCore redisCore = new RedisCoreImpl();

		/*
		 * Pipeline 注册顺序：
		 * RespEncoder 是出站 Handler，CommandHandler 是入站 Handler。
		 *
		 * CommandHandler 调用 ctx.writeAndFlush() 后，
		 * 出站事件向前传播，经过 RespEncoder。
		 */
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		ByteBuf responseBuffer = null;

		try {
			/*
			 * 本测试不测试 RespDecoder，因此直接构造解码后的 RespArray：
			 *
			 * *1\r\n
			 * $4\r\n
			 * PING\r\n
			 */
			RespArray pingRequest = new RespArray(new Resp[]{
					new BulkString(new BytesWrapper(
							"PING".getBytes(StandardCharsets.UTF_8)
					))
			});

			// 模拟一条 RespArray 入站消息进入 Pipeline。
			channel.writeInbound(pingRequest);

			/*
			 * CommandHandler 写出 SimpleString("PONG")，
			 * RespEncoder 再将它编码成 ByteBuf。
			 */
			responseBuffer = channel.readOutbound();

			Assert.assertNotNull(responseBuffer);
			Assert.assertEquals(
					"+PONG\r\n",
					responseBuffer.toString(StandardCharsets.UTF_8)
			);
		} finally {
			// ByteBuf 使用引用计数管理内存，读取后由测试负责释放。
			if (responseBuffer != null) {
				responseBuffer.release();
			}

			// 释放 EmbeddedChannel 中可能残留的资源。
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试同一 Handler 连续执行 SET 和 GET 时共享 RedisCore。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Test
	public void testHandleSetAndGet() {
		// 同一个 RedisCore 会被注入当前 Channel 的 CommandHandler。
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// 第一次入站：执行 SET name twopair。
			channel.writeInbound(command("SET", "name", "twopair"));

			// SET 返回 SimpleString("OK")，编码结果为 +OK\r\n。
			Assert.assertEquals(
					"+OK\r\n",
					readOutboundAsString(channel)
			);

			// 第二次入站：在同一个 Handler 中执行 GET name。
			channel.writeInbound(command("GET", "name"));

			// twopair 有 7 个 UTF-8 字节，因此返回长度为 7 的 BulkString。
			Assert.assertEquals(
					"$7\r\ntwopair\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试未知命令返回 RESP Error，并且 Channel 可以继续处理命令。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testHandleUnknownCommandAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// UNKNOWN 是合法 RESP 请求，但不是当前支持的 Redis 命令。
			channel.writeInbound(command("UNKNOWN"));

			// CommandHandler 应把业务异常转换成 RESP Error。
			Assert.assertEquals(
					"-ERR 不支持的命令: UNKNOWN\r\n",
					readOutboundAsString(channel)
			);

			// 业务错误不能关闭连接。
			Assert.assertTrue(channel.isOpen());

			// 错误之后再次发送 PING，证明当前连接仍然可以继续使用。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试非 RespArray 请求返回错误，并且 Channel 可以继续使用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNonArrayAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// SimpleString 是合法 RESP 类型，但不是合法的 Redis 命令结构。
			channel.writeInbound(new SimpleString("PING"));

			// Handler 应主动返回 RESP Error，不能让 Java 异常逃出 Pipeline。
			Assert.assertEquals(
					"-ERR 命令必须使用RESP Array\r\n",
					readOutboundAsString(channel)
			);

			// 输入结构错误后，当前连接仍应保持可用。
			Assert.assertTrue(channel.isOpen());

			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试 NIL RespArray 返回错误响应，并且不会关闭连接。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNilArrayAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();

		// 出站事件从后向前传播，因此 RespEncoder 放在 CommandHandler 前面。
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// RespArray.NIL 的外层类型是 RespArray，但内部数组为 null。
			channel.writeInbound(RespArray.NIL);

			Assert.assertEquals(
					"-ERR 命令数组不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 错误命令不应该影响后续正常命令。
			channel.writeInbound(command("PING"));
			Assert.assertEquals("+PONG\r\n", readOutboundAsString(channel));
		} finally {
			// 释放 EmbeddedChannel 中可能残留的引用计数对象。
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试命令名为 NIL BulkString 时返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNilCommandNameAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * 外层是合法 RespArray，命令名也是 BulkString 类型，
			 * 但是 BulkString.NIL 内部的 BytesWrapper 为 null。
			 */
			RespArray request = new RespArray(new Resp[]{
					BulkString.NIL
			});

			channel.writeInbound(request);

			Assert.assertEquals(
					"-ERR 命令名不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 错误请求不能影响同一连接处理后续正常命令。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试空字符串命令名返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectEmptyCommandNameAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * command("") 构造出的命令名是长度为 0 的 BulkString，
			 * 对应 RESP：*1\r\n$0\r\n\r\n。
			 */
			channel.writeInbound(command(""));

			Assert.assertEquals(
					"-ERR 命令名不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 空命令名不能影响后续正常请求。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试参数不完整的 SET 返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testHandleMalformedSetAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * SET 正常需要命令名、key、value 三个元素。
			 * 此处缺少 value，Set.setContent() 访问 array[2] 时会越界。
			 */
			channel.writeInbound(command("SET", "name"));

			Assert.assertEquals(
					"-ERR 命令执行失败\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 单次错误不能导致当前长连接失效。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试非法 RESP 返回协议错误，并在响应写出后关闭 Channel。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testProtocolErrorResponseAndCloseChannel() {
		RedisCore redisCore = new RedisCoreImpl();

		/*
		 * 这次测试完整入站链路：
		 * ByteBuf -> RespDecoder -> CommandHandler。
		 */
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespDecoder(),
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * '?' 不是任何合法 RESP 类型首字节，
			 * RespDecoder 会抛出协议解析异常。
			 */
			channel.writeInbound(Unpooled.copiedBuffer(
					"?\r\n",
					StandardCharsets.UTF_8
			));

			// 执行可能由 ChannelFutureListener 安排的关闭任务。
			channel.runPendingTasks();

			Assert.assertEquals(
					"-ERR Protocol error: 未知RESP类型: ?\r\n",
					readOutboundAsString(channel)
			);

			// 协议错误后无法可靠判断后续字节边界，因此必须关闭连接。
			Assert.assertFalse(channel.isOpen());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 将 Redis 命令参数构造成 redis-cli 解码后的 RespArray。
	 * @date 2026/7/13
	 * @twopair
	 */
	private RespArray command(String... arguments) {
		Resp[] array = new Resp[arguments.length];

		for (int i = 0; i < arguments.length; i++) {
			// redis-cli 发送的命令名称和参数都使用 BulkString。
			array[i] = new BulkString(new BytesWrapper(
					arguments[i].getBytes(StandardCharsets.UTF_8)
			));
		}

		return new RespArray(array);
	}

	/**
	 * @author ljj
	 * @description 读取并释放 CommandHandler 经过 RespEncoder 产生的出站 ByteBuf。
	 * @date 2026/7/13
	 * @twopair
	 */
	private String readOutboundAsString(EmbeddedChannel channel) {
		ByteBuf responseBuffer = channel.readOutbound();
		Assert.assertNotNull(responseBuffer);

		try {
			return responseBuffer.toString(StandardCharsets.UTF_8);
		} finally {
			// readOutbound 后，ByteBuf 的释放责任属于测试代码。
			responseBuffer.release();
		}
	}
}