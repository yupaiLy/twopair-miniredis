package cn.twopair.persistence.aof;

import cn.twopair.resp.RespArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * 负责将AOF Rewrite命令写入临时文件，并原子替换正式AOF文件。
 *
 * <p>临时文件与正式文件位于同一目录，确保原子移动不会跨越文件系统。
 * Rewrite失败时正式AOF保持不变，未提交的临时文件会在关闭时删除。
 *
 * @author ljj
 */
public final class AofRewriteFile implements AutoCloseable {

	private final Path targetPath;
	private final Path temporaryPath;
	private final AofFile temporaryFile;

	private boolean committed;
	private boolean closed;

	/**
	 * 为指定正式AOF文件创建同目录临时Rewrite文件。
	 *
	 * @param targetPath 正式AOF文件路径
	 * @throws NullPointerException     targetPath为 {@code null} 时抛出
	 * @throws IllegalArgumentException targetPath没有文件名时抛出
	 * @throws IOException              目录或临时文件创建失败时抛出
	 */
	public AofRewriteFile(Path targetPath) throws IOException {
		Objects.requireNonNull(targetPath, "正式AOF文件路径不能为空");
		this.targetPath = targetPath.toAbsolutePath();
		Path fileName = this.targetPath.getFileName();

		if (fileName == null) {
			throw new IllegalArgumentException("正式AOF文件路径必须包含文件名");
		}

		Path parent = this.targetPath.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		this.temporaryPath = Files.createTempFile(parent, fileName + ".rewrite-", ".tmp");

		try {
			this.temporaryFile = new AofFile(temporaryPath);
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(temporaryPath);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	/**
	 * 将一批Rewrite恢复命令追加到临时文件。
	 *
	 * @param commands 需要写入的恢复命令
	 * @throws IOException           临时文件写入失败时抛出
	 * @throws IllegalStateException Rewrite文件已经关闭或提交时抛出
	 */
	public synchronized void appendAll(List<RespArray> commands) throws IOException {
		ensureOpen();
		temporaryFile.appendAll(commands);
	}

	/**
	 * 刷盘并关闭临时文件，然后原子替换正式AOF文件。
	 *
	 * <p>原子移动失败时不会使用普通移动降级，避免正式AOF处于不完整状态。
	 *
	 * @throws IOException           刷盘、关闭或原子替换失败时抛出
	 * @throws IllegalStateException Rewrite文件已经关闭或提交时抛出
	 */
	public synchronized void commit() throws IOException {
		ensureOpen();
		temporaryFile.force();
		temporaryFile.close();
		closed = true;

		try {
			Files.move(temporaryPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			committed = true;
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(temporaryPath);
			} catch (IOException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	/**
	 * 关闭临时文件，并删除尚未提交的临时文件。
	 *
	 * @throws IOException 关闭文件或删除临时文件失败时抛出
	 */
	@Override
	public synchronized void close() throws IOException {
		if (committed) {
			return;
		}

		IOException failure = null;

		if (!closed) {
			try {
				temporaryFile.close();
			} catch (IOException exception) {
				failure = exception;
			}
			closed = true;
		}

		try {
			Files.deleteIfExists(temporaryPath);
		} catch (IOException exception) {
			if (failure == null) {
				failure = exception;
			} else {
				failure.addSuppressed(exception);
			}
		}

		if (failure != null) {
			throw failure;
		}
	}

	/**
	 * 校验临时Rewrite文件仍然可以写入或提交。
	 *
	 * @throws IllegalStateException Rewrite文件已经关闭或提交时抛出
	 */
	private void ensureOpen() {
		if (closed || committed) {
			throw new IllegalStateException("AOF Rewrite文件已经关闭或提交");
		}
	}
}