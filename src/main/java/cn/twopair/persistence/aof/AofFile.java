package cn.twopair.persistence.aof;

import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * @author ljj
 * @description 负责将Redis写命令按照RESP格式追加并刷入AOF文件。
 * @date 2026/8/18
 * @twopair
 */
public final class AofFile implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(AofFile.class);

	private final FileChannel channel;
	private final Path path;

	/**
	 * 打开指定的AOF文件，不存在时自动创建。
	 *
	 * @param path AOF文件路径
	 * @throws IOException 当目录创建或文件打开失败时抛出
	 */
	public AofFile(Path path) throws IOException {
		Objects.requireNonNull(path, "AOF文件路径不能为空");

		Path absolutePath = path.toAbsolutePath();
		Path parent = absolutePath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		this.path = absolutePath;
		this.channel = FileChannel.open(
				absolutePath,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE,
				StandardOpenOption.APPEND
		);
		LOGGER.debug("AOF文件已打开: path={}", absolutePath);
	}

	/**
	 * 将一条命令追加到AOF文件。
	 *
	 * @param command 需要持久化的RESP数组命令
	 * @throws IOException 当文件写入或刷盘失败时抛出
	 */
	public void append(RespArray command) throws IOException {
		Objects.requireNonNull(command, "AOF命令不能为空");
		appendAll(List.of(command));
	}

	/**
	 * 将一批命令编码后一次性追加到AOF文件。
	 *
	 * <p>同一批命令共用一个缓冲区，并且只执行一次刷盘。
	 *
	 * @param commands 需要按顺序持久化的命令列表
	 * @throws IOException 当文件写入或刷盘失败时抛出
	 */
	public synchronized void appendAll(List<RespArray> commands) throws IOException {
		Objects.requireNonNull(commands, "AOF命令列表不能为空");

		if (commands.isEmpty()) {
			return;
		}

		ByteBuf buffer = Unpooled.buffer();

		try {
			for (RespArray command : commands) {
				Objects.requireNonNull(command, "AOF命令不能为空");

				// 所有命令连续编码到同一个ByteBuf，保持列表中的顺序。
				Resp.encode(command, buffer);
			}

			byte[] bytes = new byte[buffer.readableBytes()];
			buffer.readBytes(bytes);

			ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);

			// FileChannel不能保证一次write写完，因此需要循环检查。
			while (byteBuffer.hasRemaining()) {
				channel.write(byteBuffer);
			}

			// 同一批命令只刷盘一次，减少磁盘同步次数。
			channel.force(false);
			LOGGER.debug("AOF追加完成: path={}, commands={}, bytes={}", path, commands.size(), bytes.length);
		} finally {
			buffer.release();
		}
	}

	/**
	 * 关闭AOF文件通道。
	 *
	 * @throws IOException 当文件通道关闭失败时抛出
	 */
	@Override
	public synchronized void close() throws IOException {
		channel.close();
		LOGGER.debug("AOF文件已关闭: path={}", path);
	}
}
