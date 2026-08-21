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
 * 负责将Redis写命令编码并追加到AOF文件，同时提供显式刷盘能力。
 *
 * @author ljj
 */
public final class AofFile implements AofStorage {
	private static final Logger LOGGER = LoggerFactory.getLogger(AofFile.class);

	private final FileChannel channel;
	private final Path path;

	/**
	 * 打开指定的AOF文件，不存在时自动创建。
	 *
	 * @param path AOF文件路径
	 * @throws NullPointerException path为 {@code null} 时抛出
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
	 * @throws NullPointerException command为 {@code null} 时抛出
	 * @throws IOException 当文件写入失败时抛出
	 */
	public void append(RespArray command) throws IOException {
		Objects.requireNonNull(command, "AOF命令不能为空");
		appendAll(List.of(command));
	}

	/**
	 * 将一批命令编码后一次性追加到AOF文件。
	 *
	 * <p>该方法只负责写入，不主动执行强制刷盘。
	 * 调用方根据AOF策略决定何时调用 {@code force()}。
	 *
	 * @param commands 需要按顺序持久化的命令列表
	 * @throws NullPointerException commands或其中任意命令为 {@code null} 时抛出
	 * @throws IOException 当文件写入失败时抛出
	 */
	@Override
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
			LOGGER.debug("AOF追加完成: path={}, commands={}, bytes={}", path, commands.size(), bytes.length);
		} finally {
			buffer.release();
		}
	}

	/**
	 * 将已经写入操作系统文件缓存的AOF内容强制刷入磁盘。
	 *
	 * <p>参数使用 {@code force(false)}，表示只要求同步文件内容，不强制同步文件元数据。
	 *
	 * @throws IOException 当刷盘失败时抛出
	 */
	@Override
	public synchronized void force() throws IOException {
		channel.force(false);
		LOGGER.debug("AOF刷盘完成: path={}", path);
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
