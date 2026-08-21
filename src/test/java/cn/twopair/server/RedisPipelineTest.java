package cn.twopair.server;

import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 验证MiniRedis真实Pipeline对同一读取批次响应的flush合并行为。
 * @date 2026/8/21
 * @twopair
 */
public class RedisPipelineTest {

	/**
	 * 验证同一读取批次中的两条命令返回两个响应，但只触发一次底层flush。
	 */
	@Test
	public void testFlushOnceForPipelinedCommands() {
		FlushCountingHandler flushCounter = new FlushCountingHandler();
		EmbeddedChannel channel = new EmbeddedChannel(flushCounter);
		RedisPipeline.configure(channel.pipeline(), new RedisCoreImpl(), null);
		ByteBuf firstResponse = null;
		ByteBuf secondResponse = null;

		try {
			channel.writeInbound(command("PING"), command("PING"));
			firstResponse = channel.readOutbound();
			secondResponse = channel.readOutbound();

			Assert.assertNotNull(firstResponse);
			Assert.assertNotNull(secondResponse);
			Assert.assertEquals("+PONG\r\n", firstResponse.toString(StandardCharsets.UTF_8));
			Assert.assertEquals("+PONG\r\n", secondResponse.toString(StandardCharsets.UTF_8));
			Assert.assertEquals(1, flushCounter.flushCount());
		} finally {
			if (firstResponse != null) {
				firstResponse.release();
			}
			if (secondResponse != null) {
				secondResponse.release();
			}
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * 验证同一读取批次超过256条命令时，达到阈值立即flush一次，读取结束后再flush剩余响应。
	 */
	@Test
	public void testFlushTwiceWhenPipelinedCommandsExceedThreshold() {
		FlushCountingHandler flushCounter = new FlushCountingHandler();
		EmbeddedChannel channel = new EmbeddedChannel(flushCounter);
		RedisPipeline.configure(channel.pipeline(), new RedisCoreImpl(), null);
		Object[] commands = new Object[300];

		for (int i = 0; i < commands.length; i++) {
			commands[i] = command("PING");
		}

		try {
			channel.writeInbound(commands);

			for (int i = 0; i < commands.length; i++) {
				ByteBuf response = channel.readOutbound();

				try {
					Assert.assertNotNull(response);
					Assert.assertEquals("+PONG\r\n", response.toString(StandardCharsets.UTF_8));
				} finally {
					if (response != null) {
						response.release();
					}
				}
			}

			Assert.assertNull(channel.readOutbound());
			Assert.assertEquals(2, flushCounter.flushCount());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * 验证批量命令中的未知命令返回错误响应，同时不影响响应顺序、flush合并和连接状态。
	 */
	@Test
	public void testFlushOnceWhenPipelinedCommandsContainUnknownCommand() {
		FlushCountingHandler flushCounter = new FlushCountingHandler();
		EmbeddedChannel channel = new EmbeddedChannel(flushCounter);
		RedisPipeline.configure(channel.pipeline(), new RedisCoreImpl(), null);
		ByteBuf firstResponse = null;
		ByteBuf secondResponse = null;

		try {
			channel.writeInbound(command("PING"), command("UNKNOWN"));
			firstResponse = channel.readOutbound();
			secondResponse = channel.readOutbound();

			Assert.assertNotNull(firstResponse);
			Assert.assertNotNull(secondResponse);
			Assert.assertEquals("+PONG\r\n", firstResponse.toString(StandardCharsets.UTF_8));
			Assert.assertEquals("-ERR 不支持的命令: UNKNOWN\r\n", secondResponse.toString(StandardCharsets.UTF_8));
			Assert.assertNull(channel.readOutbound());
			Assert.assertEquals(1, flushCounter.flushCount());
			Assert.assertTrue(channel.isOpen());
		} finally {
			if (firstResponse != null) {
				firstResponse.release();
			}
			if (secondResponse != null) {
				secondResponse.release();
			}
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * 验证批量请求发生协议异常时先刷出已完成的响应，再返回协议错误并关闭连接。
	 */
	@Test
	public void testFlushPendingResponseBeforeClosingOnProtocolError() {
		FlushCountingHandler flushCounter = new FlushCountingHandler();
		EmbeddedChannel channel = new EmbeddedChannel(flushCounter);
		RedisPipeline.configure(channel.pipeline(), new RedisCoreImpl(), null);
		ByteBuf firstResponse = null;
		ByteBuf secondResponse = null;

		try {
			channel.writeInbound(Unpooled.copiedBuffer("*1\r\n$4\r\nPING\r\n?\r\n", StandardCharsets.UTF_8));
			channel.runPendingTasks();
			firstResponse = channel.readOutbound();
			secondResponse = channel.readOutbound();

			Assert.assertNotNull(firstResponse);
			Assert.assertNotNull(secondResponse);
			Assert.assertEquals("+PONG\r\n", firstResponse.toString(StandardCharsets.UTF_8));
			Assert.assertEquals("-ERR Protocol error: 未知RESP类型: ?\r\n", secondResponse.toString(StandardCharsets.UTF_8));
			Assert.assertNull(channel.readOutbound());
			Assert.assertEquals(2, flushCounter.flushCount());
			Assert.assertFalse(channel.isOpen());
		} finally {
			if (firstResponse != null) {
				firstResponse.release();
			}
			if (secondResponse != null) {
				secondResponse.release();
			}
			channel.finishAndReleaseAll();
		}
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
			array[i] = new BulkString(new BytesWrapper(arguments[i].getBytes(StandardCharsets.UTF_8)));
		}

		return new RespArray(array);
	}

	/**
	 * 记录传播到底层传输层的flush次数。
	 */
	private static final class FlushCountingHandler extends ChannelOutboundHandlerAdapter {
		private int flushCount;

		/**
		 * 记录并继续传播flush事件。
		 *
		 * @param ctx 当前Channel上下文
		 * @throws Exception flush事件传播失败时抛出
		 */
		@Override
		public void flush(ChannelHandlerContext ctx) throws Exception {
			flushCount++;
			ctx.flush();
		}

		/**
		 * 获取已经传播的flush次数。
		 *
		 * @return flush次数
		 */
		private int flushCount() {
			return flushCount;
		}
	}
}
