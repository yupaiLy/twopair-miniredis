package cn.twopair.server.handler;

import cn.twopair.command.Command;
import cn.twopair.command.CommandFactory;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
import cn.twopair.persistence.aof.AofFile;
import cn.twopair.resp.Errors;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.util.TraceIdGenerator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.List;

/**
 * @author ljj
 * @description 将 RESP 请求转换成 Redis 命令并执行。
 * @date 2026/7/13
 * @twopair
 */
public class CommandHandler extends SimpleChannelInboundHandler<Resp> {
	private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);

	/**
	 * AOF文件；为null表示当前服务未启用AOF。
	 */
	private final AofFile aofFile;

	/**
	 * 所有客户端连接共享的 Redis 核心存储。
	 */
	private final RedisCore redisCore;

	/**
	 * 创建未启用AOF的命令处理器。
	 *
	 * @param redisCore 所有连接共享的Redis核心存储
	 */
	public CommandHandler(RedisCore redisCore) {
		this(redisCore, null);
	}

	/**
	 * 创建启用AOF的命令处理器。
	 *
	 * @param redisCore 所有连接共享的Redis核心存储
	 * @param aofFile   用于记录成功写命令的AOF文件
	 */
	public CommandHandler(RedisCore redisCore, AofFile aofFile) {
		this.redisCore = redisCore;
		this.aofFile = aofFile;
	}

	/**
	 * 记录客户端连接建立事件。
	 *
	 * @param ctx 当前连接上下文
	 * @throws Exception 当后续Handler处理连接事件失败时抛出
	 */
	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		LOGGER.info(
				"客户端连接建立: connectionId={}, remoteAddress={}",
				connectionId(ctx),
				remoteAddress(ctx)
		);
		super.channelActive(ctx);
	}

	/**
	 * 记录客户端连接断开事件。
	 *
	 * @param ctx 当前连接上下文
	 * @throws Exception 当后续Handler处理断开事件失败时抛出
	 */
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		LOGGER.info(
				"客户端连接断开: connectionId={}, remoteAddress={}",
				connectionId(ctx),
				remoteAddress(ctx)
		);
		super.channelInactive(ctx);
	}

	/**
	 * @author ljj
	 * @description 接收完整 RESP，执行对应命令并写回响应。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Resp resp) {
		String traceId = TraceIdGenerator.next();
		String commandName = "UNKNOWN";
		long startNanos = System.nanoTime();
		MDC.put("traceId", traceId);

		try {
			// redis-cli 发送的命令必须是 RESP Array。
			if (!(resp instanceof RespArray commandArray)) {
				LOGGER.warn(
						"拒绝非数组命令: connectionId={}, requestType={}",
						connectionId(ctx),
						resp.getClass().getSimpleName()
				);
				writeError(ctx, "命令必须使用RESP Array");
				return;
			}

			// 创建命令时会完成参数解析和基础校验。
			Command command = CommandFactory.from(commandArray);
			commandName = command.type().name();
			LOGGER.debug(
					"开始处理命令: connectionId={}, remoteAddress={}, command={}",
					connectionId(ctx),
					remoteAddress(ctx),
					commandName
			);

			Resp response = executeCommand(command, commandArray);
			ChannelFuture responseFuture = ctx.writeAndFlush(response);
			String completedCommandName = commandName;
			responseFuture.addListener(future -> {
				if (!future.isSuccess()) {
					LOGGER.error(
							"响应写入失败: traceId={}, connectionId={}, command={}",
							traceId,
							connectionId(ctx),
							completedCommandName,
							future.cause()
					);
				}
			});

			LOGGER.debug(
					"命令处理完成: connectionId={}, command={}, responseType={}, elapsedMicros={}",
					connectionId(ctx),
					commandName,
					response.getClass().getSimpleName(),
					elapsedMicros(startNanos)
			);
		} catch (IOException e) {
			/*
			 * 内存命令已经执行，但AOF持久化失败。
			 * 当前阶段返回错误，后续再完善写入失败后的服务保护策略。
			 */
			LOGGER.error(
					"命令AOF持久化失败: connectionId={}, command={}, elapsedMicros={}",
					connectionId(ctx),
					commandName,
					elapsedMicros(startNanos),
					e
			);
			writeError(ctx, "AOF持久化失败");
		} catch (WrongTypeException e) {
			/*
			 * WRONGTYPE是Redis标准错误类型，不能使用writeError()，
			 * 因为writeError()会自动增加"ERR "前缀。
			 */
			LOGGER.debug(
					"命令类型不匹配: connectionId={}, command={}, elapsedMicros={}",
					connectionId(ctx),
					commandName,
					elapsedMicros(startNanos)
			);
			ctx.writeAndFlush(new Errors(e.getMessage()));
		} catch (IllegalArgumentException e) {
			/*
			 * 可预期的输入错误，例如空命令名、未知命令。
			 * 保留明确的错误信息，帮助客户端理解问题。
			 */
			String message = e.getMessage() == null ? "命令参数错误" : e.getMessage();
			LOGGER.warn(
					"命令被拒绝: connectionId={}, command={}, reason={}, elapsedMicros={}",
					connectionId(ctx),
					commandName,
					message,
					elapsedMicros(startNanos)
			);
			writeError(ctx, message);
		} catch (RuntimeException e) {
			/*
			 * 临时兜住现有命令尚未完成参数校验时产生的运行时异常。
			 * 不直接返回 e.getMessage()，避免暴露数组下标等内部实现。
			 */
			LOGGER.error(
					"命令执行发生未预期异常: connectionId={}, command={}, elapsedMicros={}",
					connectionId(ctx),
					commandName,
					elapsedMicros(startNanos),
					e
			);
			writeError(ctx, "命令执行失败");
		} finally {
			MDC.remove("traceId");
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
		String traceId = TraceIdGenerator.next();
		MDC.put("traceId", traceId);

		try {
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

			LOGGER.error(
					"连接处理异常，将返回协议错误并关闭连接: connectionId={}, remoteAddress={}, reason={}",
					connectionId(ctx),
					remoteAddress(ctx),
					message,
					cause
			);

			/*
			 * writeAndFlush 是异步操作。
			 * 先保存返回的 Future，再监听写操作完成并关闭连接。
			 */
			ChannelFuture future = writeError(ctx, message);
			future.addListener(ChannelFutureListener.CLOSE);
		} finally {
			MDC.remove("traceId");
		}
	}

	/**
	 * 执行命令，并保证写命令的内存修改顺序与AOF追加顺序一致。
	 *
	 * @param command         已完成参数解析的命令
	 * @param originalCommand 客户端发送的原始RESP命令
	 * @return 命令执行结果
	 * @throws IOException 当AOF写入失败时抛出
	 */
	private Resp executeCommand(Command command, RespArray originalCommand) throws IOException {
		if (aofFile == null || !(command instanceof WriteCommand writeCommand)) {
			return command.handle(redisCore);
		}

		/*
		 * 所有CommandHandler共享同一个AofFile，因此可以把它作为写锁。
		 * 锁内同时完成内存修改和AOF追加，防止其他连接插入写命令。
		 */
		synchronized (aofFile) {
			Resp response = command.handle(redisCore);

			List<RespArray> aofCommands =
					writeCommand.toAofCommands(originalCommand, redisCore);
			aofFile.appendAll(aofCommands);

			return response;
		}
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

	/**
	 * 获取当前连接的短标识。
	 *
	 * @param ctx 当前连接上下文
	 * @return Netty channel短标识
	 */
	private String connectionId(ChannelHandlerContext ctx) {
		return ctx.channel().id().asShortText();
	}

	/**
	 * 获取客户端远端地址，EmbeddedChannel等测试连接没有地址时返回unknown。
	 *
	 * @param ctx 当前连接上下文
	 * @return 远端地址文本
	 */
	private String remoteAddress(ChannelHandlerContext ctx) {
		return ctx.channel().remoteAddress() == null
				? "unknown"
				: ctx.channel().remoteAddress().toString();
	}

	/**
	 * 计算从指定时间开始经过的微秒数。
	 *
	 * @param startNanos 开始时间
	 * @return 已经过的微秒数
	 */
	private long elapsedMicros(long startNanos) {
		return (System.nanoTime() - startNanos) / 1000L;
	}
}
