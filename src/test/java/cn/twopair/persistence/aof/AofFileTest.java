package cn.twopair.persistence.aof;

import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author ljj
 * @description 测试AOF文件的单条及批量命令追加行为。
 * @date 2026/8/18
 * @twopair
 */
public class AofFileTest {

	/**
	 * 验证多个命令会按照调用顺序追加，并保持完整的RESP字节格式。
	 *
	 * @throws Exception 当临时文件创建或AOF读写失败时抛出
	 */
	@Test
	public void testAppendCommandsInRespFormat() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-", ".aof");

		try {
			try (AofFile aofFile = new AofFile(path)) {
				aofFile.append(command("SET", "name", "twopair"));
				aofFile.append(command("SET", "city", "杭州"));

				// 追加只负责写入文件，持久化策略通过force显式刷盘。
				aofFile.force();
			}

			String expected = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\nname\r\n"
					+ "$7\r\ntwopair\r\n"
					+ "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\ncity\r\n"
					+ "$6\r\n杭州\r\n";

			Assert.assertArrayEquals(
					expected.getBytes(StandardCharsets.UTF_8),
					Files.readAllBytes(path)
			);
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证同一批命令能够按照列表顺序完成一次批量追加。
	 *
	 * @throws Exception 当临时文件创建或AOF读写失败时抛出
	 */
	@Test
	public void testAppendAllCommandsInRespFormat() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-batch-", ".aof");

		try {
			try (AofFile aofFile = new AofFile(path)) {
				aofFile.appendAll(List.of(
						command("SET", "city", "杭州"),
						command("PEXPIREAT", "city", "11000")
				));

				// 一批命令写入完成后只执行一次强制刷盘。
				aofFile.force();
			}

			String expected = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\ncity\r\n"
					+ "$6\r\n杭州\r\n"
					+ "*3\r\n"
					+ "$9\r\nPEXPIREAT\r\n"
					+ "$4\r\ncity\r\n"
					+ "$5\r\n11000\r\n";

			Assert.assertArrayEquals(
					expected.getBytes(StandardCharsets.UTF_8),
					Files.readAllBytes(path)
			);
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 将字符串参数构造成redis-cli发送后的RESP数组。
	 *
	 * @param arguments 命令名称和参数
	 * @return RESP数组命令
	 */
	private RespArray command(String... arguments) {
		Resp[] array = new Resp[arguments.length];

		for (int i = 0; i < arguments.length; i++) {
			array[i] = new BulkString(new BytesWrapper(
					arguments[i].getBytes(StandardCharsets.UTF_8)
			));
		}

		return new RespArray(array);
	}
}
