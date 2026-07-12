package cn.twopair.server.codec;

import cn.twopair.resp.BulkString;
import cn.twopair.resp.RespArray;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description RESP 入站解码器测试。
 * @date 2026/7/10
 * @twopair
 */
public class RespDecoderTest {

	/**
	 * @author ljj
	 * @description 测试 Decoder 能同时处理 TCP 拆包和粘包。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testDecodeSplitAndCoalescedRequest() {
		// EmbeddedChannel 是内存中的 Netty Channel，不需要启动真实服务端。
		EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());

		try {
			// 第一次只发送 PING 请求的前半段，不能输出半成品 Resp。
			Assert.assertFalse(channel.writeInbound(
					Unpooled.copiedBuffer("*1\r\n$4\r\nPI", StandardCharsets.UTF_8)
			));
			Assert.assertNull(channel.readInbound());

			// 第二次补齐请求后，Decoder 应输出一个完整的 PING 数组。
			Assert.assertTrue(channel.writeInbound(
					Unpooled.copiedBuffer("NG\r\n", StandardCharsets.UTF_8)
			));
			RespArray splitPing = channel.readInbound();
			Assert.assertEquals(
					"PING",
					((BulkString) splitPing.getArray()[0]).getBytesWrapper().toUtf8String()
			);

			// 一次写入两条完整 PING，模拟 TCP 粘包。
			String pingRequest = "*1\r\n$4\r\nPING\r\n";
			Assert.assertTrue(channel.writeInbound(
					Unpooled.copiedBuffer(pingRequest + pingRequest, StandardCharsets.UTF_8)
			));

			// Decoder 应依次输出两条命令，而不是把它们合并成一条。
			RespArray firstPing = channel.readInbound();
			RespArray secondPing = channel.readInbound();
			Assert.assertEquals(
					"PING",
					((BulkString) firstPing.getArray()[0]).getBytesWrapper().toUtf8String()
			);
			Assert.assertEquals(
					"PING",
					((BulkString) secondPing.getArray()[0]).getBytesWrapper().toUtf8String()
			);
			Assert.assertNull(channel.readInbound());
		} finally {
			// 释放 EmbeddedChannel 可能持有的 Netty 缓冲区资源。
			channel.finishAndReleaseAll();
		}
	}
}