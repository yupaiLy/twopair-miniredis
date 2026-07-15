package cn.twopair.server.handler;

import cn.twopair.command.Command;
import cn.twopair.command.CommandFactory;
import cn.twopair.core.RedisCore;
import cn.twopair.resp.Errors;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * @author ljj
 * @description 将 RESP 请求转换成 Redis 命令并执行。
 * @date 2026/7/13
 * @twopair
 */
public class CommandHandler extends SimpleChannelInboundHandler<Resp> {

	/**
	 * 所有客户端连接共享的 Redis 核心存储。
	 */
	private final RedisCore redisCore;

	/**
	 * @author ljj
	 * @description 通过构造器注入共享的 RedisCore。
	 * @date 2026/7/13
	 * @twopair
	 */
	public CommandHandler(RedisCore redisCore) {
		this.redisCore = redisCore;
	}

	/**
	 * @author ljj
	 * @description 接收完整 RESP，执行对应命令并写回响应。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Resp resp) {
		// redis-cli 发送的命令必须是 RESP Array。
		if (!(resp instanceof RespArray)) {
			writeError(ctx, "命令必须使用RESP Array");
			return;
		}

		try {
			// 根据数组中的命令名称创建 PING、SET 或 GET 对象。
			Command command = CommandFactory.from((RespArray) resp);

			// 命令通过共享的 RedisCore 执行业务逻辑。
			Resp response = command.handle(redisCore);

			/*
			 * 写出 Resp 对象并立即刷新。
			 * 出站事件会向前经过 RespEncoder，最终变成 ByteBuf。
			 */
			ctx.writeAndFlush(response);
		} catch (IllegalArgumentException e) {
			/*
			 * 可预期的输入错误，例如空命令名、未知命令。
			 * 保留明确的错误信息，帮助客户端理解问题。
			 */
			String message = e.getMessage() == null ? "命令参数错误" : e.getMessage();
			writeError(ctx, message);
		} catch (RuntimeException e) {
			/*
			 * 临时兜住现有命令尚未完成参数校验时产生的运行时异常。
			 * 不直接返回 e.getMessage()，避免暴露数组下标等内部实现。
			 */
			writeError(ctx, "命令执行失败");
		}

	}

	/**
	 * Handles exceptions that occur during the handling of channel events. This method processes the root cause of the
	 * exception, determines an appropriate error message, and sends a protocol-specific error response back to the client
	 * before closing the connection.
	 *
	 * @param ctx   the {@code ChannelHandlerContext} for interacting with the pipeline and sending responses to the client
	 * @param cause the {@code Throwable} representing the exception that was caught during processing
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {

		Throwable rootCause = cause;
		/*
		 * RespDecoder 抛出的 IllegalStateException 通常会被 Netty
		 * 包装成 DecoderException，因此向下查找真正的根异常。
		 */
		while (rootCause.getCause() != null
				&& rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}

		String detail = rootCause.getMessage();
		String message = "Protocol error";

		// 只在存在明确错误信息时追加详细原因。
		if (detail != null && !detail.isBlank()) {
			message = message + ": " + detail;
		}

		/*
		 * writeAndFlush 是异步操作。
		 * 先保存返回的 Future，再监听写操作完成并关闭连接。
		 */
		ChannelFuture future = writeError(ctx, message);
		future.addListener(ChannelFutureListener.CLOSE);
	}

	/**
	 * Writes an error message to the client using RESP (Redis Serialization Protocol) format.
	 * The error message will be encoded into a RESP error starting with a '-' prefix.
	 *
	 * @param ctx     the {@code ChannelHandlerContext} used to write the error response back to the client
	 * @param message the error message content to be sent to the client
	 * @return a {@code ChannelFuture} representing the asynchronous operation of writing and flushing the error message
	 */
	private ChannelFuture writeError(ChannelHandlerContext ctx, String message) {
		// Errors 会由 RespEncoder 编码成以 '-' 开头的 RESP 错误。
		return ctx.writeAndFlush(new Errors("ERR " + message));
	}
}