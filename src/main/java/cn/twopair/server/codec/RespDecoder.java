package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 将入站 ByteBuf 解码为完整 RESP 对象。
 *
 * @author ljj
 */
public class RespDecoder extends ByteToMessageDecoder {

	/**
	 * 尝试读取一条完整 RESP；半包时不输出对象，等待后续字节。
	 *
	 * @param ctx 当前连接的Handler上下文
	 * @param in  Netty累积的入站字节缓冲区
	 * @param out 接收完整RESP对象的输出列表
	 * @throws RuntimeException RESP数据违反协议格式时抛出
	 */
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		try {
			// tryDecode会在TCP半包时回滚readerIndex并返回null。
			Resp resp = Resp.tryDecode(in);

			// 只有完整RESP才能进入后续业务Handler。
			if (resp != null) {
				out.add(resp);
			}
		} catch (RuntimeException e) {
			/*
			 * 协议已经损坏，当前连接随后会被关闭。
			 * 丢弃剩余字节，避免关闭连接时decodeLast再次解析残留数据。
			 */
			in.skipBytes(in.readableBytes());
			throw e;
		}
	}
}
