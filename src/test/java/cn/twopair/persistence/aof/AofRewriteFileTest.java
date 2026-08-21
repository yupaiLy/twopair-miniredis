package cn.twopair.persistence.aof;

import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 验证AOF Rewrite临时文件的写入、提交、取消和清理行为。
 *
 * @author ljj
 */
public class AofRewriteFileTest {

	/**
	 * 验证提交前正式AOF保持不变，提交后新文件能够完整重放。
	 *
	 * @throws Exception 临时目录或AOF文件操作失败时抛出
	 */
	@Test
	public void testCommitReplacesTargetAndReplaysCommands() throws Exception {
		Path directory = Files.createTempDirectory("twopair-aof-rewrite-commit-");
		Path targetPath = directory.resolve("appendonly.aof");

		try {
			writeCommands(targetPath, List.of(command("SET", "old", "value")));
			byte[] originalBytes = Files.readAllBytes(targetPath);

			try (AofRewriteFile rewriteFile = new AofRewriteFile(targetPath)) {
				rewriteFile.appendAll(List.of(command("SET", "name", "老板"), command("SET", "city", "杭州")));
				Assert.assertArrayEquals(originalBytes, Files.readAllBytes(targetPath));
				rewriteFile.commit();
			}

			RedisCoreImpl restored = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(targetPath, restored));
			Assert.assertNull(restored.get(bytes("old")));
			Assert.assertEquals("老板", ((RedisString) restored.get(bytes("name"))).getValue().toUtf8String());
			Assert.assertEquals("杭州", ((RedisString) restored.get(bytes("city"))).getValue().toUtf8String());
			Assert.assertFalse(hasRewriteTemporaryFile(directory));
		} finally {
			deleteDirectory(directory);
		}
	}

	/**
	 * 验证未提交Rewrite时关闭对象会删除临时文件，并保留原始AOF内容。
	 *
	 * @throws Exception 临时目录或AOF文件操作失败时抛出
	 */
	@Test
	public void testCloseWithoutCommitKeepsTargetAndDeletesTemporaryFile() throws Exception {
		Path directory = Files.createTempDirectory("twopair-aof-rewrite-cancel-");
		Path targetPath = directory.resolve("appendonly.aof");

		try {
			writeCommands(targetPath, List.of(command("SET", "name", "old")));
			byte[] originalBytes = Files.readAllBytes(targetPath);

			try (AofRewriteFile rewriteFile = new AofRewriteFile(targetPath)) {
				rewriteFile.appendAll(List.of(command("SET", "name", "new")));
				Assert.assertTrue(hasRewriteTemporaryFile(directory));
			}

			Assert.assertArrayEquals(originalBytes, Files.readAllBytes(targetPath));
			Assert.assertFalse(hasRewriteTemporaryFile(directory));
		} finally {
			deleteDirectory(directory);
		}
	}

	/**
	 * 验证空数据集提交后会生成合法的空AOF文件。
	 *
	 * @throws Exception 临时目录或AOF文件操作失败时抛出
	 */
	@Test
	public void testCommitEmptyRewriteFile() throws Exception {
		Path directory = Files.createTempDirectory("twopair-aof-rewrite-empty-");
		Path targetPath = directory.resolve("appendonly.aof");

		try {
			writeCommands(targetPath, List.of(command("SET", "old", "value")));

			try (AofRewriteFile rewriteFile = new AofRewriteFile(targetPath)) {
				rewriteFile.commit();
			}

			Assert.assertEquals(0L, Files.size(targetPath));
			Assert.assertEquals(0, AofReplay.replay(targetPath, new RedisCoreImpl()));
			Assert.assertFalse(hasRewriteTemporaryFile(directory));
		} finally {
			deleteDirectory(directory);
		}
	}

	/**
	 * 验证Rewrite文件提交或关闭后不能再次写入和提交。
	 *
	 * @throws Exception 临时目录或AOF文件操作失败时抛出
	 */
	@Test
	public void testRejectOperationsAfterCommitOrClose() throws Exception {
		Path directory = Files.createTempDirectory("twopair-aof-rewrite-state-");
		Path committedPath = directory.resolve("committed.aof");
		Path closedPath = directory.resolve("closed.aof");

		try {
			AofRewriteFile committedFile = new AofRewriteFile(committedPath);
			committedFile.commit();
			assertClosed(() -> committedFile.appendAll(List.of(command("SET", "name", "value"))));
			assertClosed(committedFile::commit);
			committedFile.close();

			AofRewriteFile closedFile = new AofRewriteFile(closedPath);
			closedFile.close();
			assertClosed(() -> closedFile.appendAll(List.of(command("SET", "name", "value"))));
			assertClosed(closedFile::commit);
		} finally {
			deleteDirectory(directory);
		}
	}

	/**
	 * 将命令写入指定AOF文件。
	 *
	 * @param path AOF文件路径
	 * @param commands 需要写入的命令
	 * @throws IOException 文件写入失败时抛出
	 */
	private void writeCommands(Path path, List<RespArray> commands) throws IOException {
		try (AofFile aofFile = new AofFile(path)) {
			aofFile.appendAll(commands);
			aofFile.force();
		}
	}

	/**
	 * 断言操作会因为Rewrite文件已经关闭而失败。
	 *
	 * @param operation 需要执行的文件操作
	 * @throws Exception 文件操作意外失败时抛出
	 */
	private void assertClosed(FileOperation operation) throws Exception {
		try {
			operation.run();
			Assert.fail("关闭或提交后的Rewrite文件不应继续操作");
		} catch (IllegalStateException exception) {
			Assert.assertEquals("AOF Rewrite文件已经关闭或提交", exception.getMessage());
		}
	}

	/**
	 * 判断目录中是否存在Rewrite临时文件。
	 *
	 * @param directory 目标目录
	 * @return 存在Rewrite临时文件时返回 {@code true}
	 * @throws IOException 目录读取失败时抛出
	 */
	private boolean hasRewriteTemporaryFile(Path directory) throws IOException {
		try (Stream<Path> paths = Files.list(directory)) {
			return paths.anyMatch(path -> path.getFileName().toString().contains(".rewrite-"));
		}
	}

	/**
	 * 删除测试创建的目录及其中的文件。
	 *
	 * @param directory 测试目录
	 * @throws IOException 文件删除失败时抛出
	 */
	private void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}

		try (Stream<Path> paths = Files.list(directory)) {
			for (Path path : paths.toList()) {
				Files.deleteIfExists(path);
			}
		}
		Files.deleteIfExists(directory);
	}

	/**
	 * 将文本参数构造成RESP数组命令。
	 *
	 * @param arguments 命令名称和参数
	 * @return RESP数组命令
	 */
	private RespArray command(String... arguments) {
		Resp[] array = new Resp[arguments.length];

		for (int i = 0; i < arguments.length; i++) {
			array[i] = new BulkString(bytes(arguments[i]));
		}

		return new RespArray(array);
	}

	/**
	 * 将文本转换为UTF-8字节包装对象。
	 *
	 * @param value 文本内容
	 * @return UTF-8字节包装对象
	 */
	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 表示可能抛出I/O异常的文件操作。
	 */
	@FunctionalInterface
	private interface FileOperation {

		/**
		 * 执行文件操作。
		 *
		 * @throws IOException 文件操作失败时抛出
		 */
		void run() throws IOException;
	}
}
