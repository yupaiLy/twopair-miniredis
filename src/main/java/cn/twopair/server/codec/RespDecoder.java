package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * @author ljj
 * @description 将入站 ByteBuf 解码为完整 RESP 对象。
 * @date 2026/7/10
 * @twopair
 */
public class RespDecoder extends ByteToMessageDecoder {

	/**
	 * @author ljj
	 * @description 尝试读取一条完整 RESP；半包时不输出对象，等待后续字节。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		// tryDecode 会在半包时回滚 readerIndex 并返回 null。
		Resp resp = Resp.tryDecode(in);

		// 只有完整 RESP 才能进入后续业务 Handler。
		if (resp != null) {
			out.add(resp);
		}
	}
}