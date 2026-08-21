package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 将出站{@link Resp RESP对象}编码为客户端可识别的RESP字节。
 *
 * @author ljj
 */
public class RespEncoder extends MessageToByteEncoder<Resp> {

	/**
	 * 将一条{@link Resp RESP响应}写入Netty提供的出站缓冲区。
	 *
	 * @param ctx  当前连接的{@link ChannelHandlerContext 通道处理器上下文}
	 * @param resp 需要编码的{@link Resp RESP响应}
	 * @param out  接收编码结果的{@link ByteBuf 字节缓冲区}
	 * @throws IllegalStateException RESP类型不受支持时抛出
	 */
	@Override
	protected void encode(ChannelHandlerContext ctx, Resp resp, ByteBuf out) {
		// 具体协议规则由 Resp 统一维护，Encoder 只负责连接 Netty Pipeline。
		Resp.encode(resp, out);
	}
}
