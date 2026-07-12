package cn.twopair.server.codec;

import cn.twopair.resp.SimpleString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description RESP 出站编码器测试。
 * @date 2026/7/11
 * @twopair
 */
public class RespEncoderTest {

	/**
	 * @author ljj
	 * @description 测试 Resp 对象能够被编码为标准 RESP 字节。
	 * @date 2026/7/11
	 * @twopair
	 */
	@Test
	public void testEncodeSimpleString() {
		// EmbeddedChannel 会模拟 Netty 的出站 Pipeline。
		EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder());
		ByteBuf encodedBuffer = null;

		try {
			// 模拟业务 Handler 写出 PONG 响应。
			Assert.assertTrue(
					channel.writeOutbound(new SimpleString("PONG"))
			);

			// 从出站队列读取编码后的 ByteBuf。
			encodedBuffer = channel.readOutbound();
			Assert.assertNotNull(encodedBuffer);

			// SimpleString 的 RESP 格式是：+内容\r\n。
			Assert.assertEquals(
					"+PONG\r\n",
					encodedBuffer.toString(StandardCharsets.UTF_8)
			);
		} finally {
			// readOutbound 后，ByteBuf 的释放责任交给测试代码。
			if (encodedBuffer != null) {
				encodedBuffer.release();
			}

			// 释放 Channel 内可能残留的 Netty 资源。
			channel.finishAndReleaseAll();
		}
	}
}