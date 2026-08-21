package cn.twopair.persistence.aof;

import cn.twopair.command.Command;
import cn.twopair.command.CommandFactory;
import cn.twopair.core.RedisCore;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 负责重放AOF命令，并修复文件末尾不完整的RESP数据。
 *
 * @author ljj
 */
public final class AofReplay {
	private static final Logger LOGGER = LoggerFactory.getLogger(AofReplay.class);

	/**
	 * 工具类不允许创建实例。
	 */
	private AofReplay() {
	}

	/**
	 * 将AOF文件中的命令重放到指定RedisCore。
	 *
	 * @param path      AOF文件路径
	 * @param redisCore 需要恢复数据的Redis核心存储
	 * @return 成功重放的命令数量
	 * @throws IOException           当AOF文件读取失败时抛出
	 * @throws IllegalStateException 当AOF内容非法或不是RESP数组时抛出
	 */
	public static int replay(
			Path path,
			RedisCore redisCore
	) throws IOException {
		Objects.requireNonNull(path, "AOF文件路径不能为空");
		Objects.requireNonNull(redisCore, "RedisCore不能为空");

		// 第一次启动时AOF文件可能尚未创建，应当视为没有历史数据。
		if (!Files.exists(path)) {
			LOGGER.debug("AOF文件不存在，跳过恢复: path={}", path.toAbsolutePath());
			return 0;
		}

		byte[] bytes = Files.readAllBytes(path);

		if (bytes.length == 0) {
			LOGGER.debug("AOF文件为空，跳过恢复: path={}", path.toAbsolutePath());
			return 0;
		}

		LOGGER.info("开始恢复AOF: path={}, bytes={}", path.toAbsolutePath(), bytes.length);

		ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
		int replayedCount = 0;
		int lastValidOffset = 0;

		try {
			while (buffer.isReadable()) {
				Resp resp = Resp.tryDecode(buffer);

				if (resp == null) {
					/*
					 * null只表示文件末尾数据不完整。
					 * 保留之前的完整命令，并删除无法恢复的尾部字节。
					 */
					truncateIncompleteTail(path, lastValidOffset);
					LOGGER.warn(
							"AOF末尾存在不完整命令，已截断: path={}, validBytes={}, discardedBytes={}",
							path.toAbsolutePath(),
							lastValidOffset,
							bytes.length - lastValidOffset
					);
					return replayedCount;
				}
				if (!(resp instanceof RespArray commandArray)) {
					throw new IllegalStateException(
							"AOF中的命令必须是RESP Array"
					);
				}

				Command command = CommandFactory.from(commandArray);
				LOGGER.debug("重放AOF命令: index={}, command={}", replayedCount + 1, command.type());

				// 重放阶段只恢复内存，不向客户端返回响应。
				command.handle(redisCore);
				replayedCount++;

				// readerIndex此时指向下一条命令的起始位置。
				lastValidOffset = buffer.readerIndex();
			}

			LOGGER.info("AOF恢复完成: path={}, replayedCommands={}", path.toAbsolutePath(), replayedCount);
			return replayedCount;
		} finally {
			buffer.release();
		}
	}

	/**
	 * 将AOF截断到最后一条完整命令的结束位置。
	 *
	 * @param path            AOF文件路径
	 * @param lastValidOffset 最后一个有效字节位置
	 * @throws IOException 当文件截断或刷盘失败时抛出
	 */
	private static void truncateIncompleteTail(
			Path path,
			long lastValidOffset
	) throws IOException {
		try (FileChannel channel = FileChannel.open(
				path,
				StandardOpenOption.WRITE
		)) {
			channel.truncate(lastValidOffset);

			// 文件长度属于元数据，因此这里使用true。
			channel.force(true);
		}
	}
}
