package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * @author ljj
 * @description 将出站 Resp 对象编码为客户端可识别的 RESP 字节。
 * @date 2026/7/11
 * @twopair
 */
public class RespEncoder extends MessageToByteEncoder<Resp> {

	/**
	 * @author ljj
	 * @description 将一条 Resp 响应写入 Netty 提供的出站缓冲区。
	 * @date 2026/7/11
	 * @twopair
	 */
	@Override
	protected void encode(ChannelHandlerContext ctx, Resp resp, ByteBuf out) {
		// 具体协议规则由 Resp 统一维护，Encoder 只负责连接 Netty Pipeline。
		Resp.encode(resp, out);
	}
}