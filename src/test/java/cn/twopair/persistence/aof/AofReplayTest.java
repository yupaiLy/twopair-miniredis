package cn.twopair.persistence.aof;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author ljj
 * @description 测试AOF文件的命令恢复及不完整尾部修复行为。
 * @date 2026/8/18
 * @twopair
 */
public class AofReplayTest {

	/**
	 * 验证AOF中的SET命令会被顺序重放到一个全新的RedisCore。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testReplaySetCommands() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-replay-", ".aof");

		try {
			try (AofFile aofFile = new AofFile(path)) {
				aofFile.append(command("SET", "name", "twopair"));
				aofFile.append(command("SET", "city", "杭州"));
			}

			RedisCore redisCore = new RedisCoreImpl();
			int replayedCount = AofReplay.replay(path, redisCore);

			Assert.assertEquals(2, replayedCount);
			Assert.assertEquals("twopair", stringValue(redisCore, "name"));
			Assert.assertEquals("杭州", stringValue(redisCore, "city"));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证AOF末尾只有半条命令时，会恢复完整命令并截断不完整尾部。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testReplayTruncatesIncompleteTail() throws Exception {
		Path path = Files.createTempFile(
				"twopair-miniredis-truncated-",
				".aof"
		);

		try {
			String completeCommand = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\nname\r\n"
					+ "$7\r\ntwopair\r\n";
			String incompleteCommand = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\ncity\r\n";

			Files.writeString(
					path,
					completeCommand + incompleteCommand,
					StandardCharsets.UTF_8
			);

			RedisCore redisCore = new RedisCoreImpl();
			int replayedCount = AofReplay.replay(path, redisCore);

			Assert.assertEquals(1, replayedCount);
			Assert.assertEquals("twopair", stringValue(redisCore, "name"));
			Assert.assertArrayEquals(
					completeCommand.getBytes(StandardCharsets.UTF_8),
					Files.readAllBytes(path)
			);
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证AOF中间出现非法RESP时会终止恢复，并且不会修改原文件。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testReplayRejectsInvalidMiddleWithoutTruncation() throws Exception {
		Path path = Files.createTempFile(
				"twopair-miniredis-invalid-",
				".aof"
		);

		try {
			String firstCommand = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\nname\r\n"
					+ "$7\r\ntwopair\r\n";
			String invalidContent = "?broken\r\n";
			String laterCommand = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\ncity\r\n"
					+ "$6\r\n杭州\r\n";
			byte[] originalBytes = (firstCommand + invalidContent + laterCommand)
					.getBytes(StandardCharsets.UTF_8);
			Files.write(path, originalBytes);

			try {
				AofReplay.replay(path, new RedisCoreImpl());
				Assert.fail("AOF中间存在非法RESP时应终止恢复");
			} catch (IllegalStateException e) {
				Assert.assertTrue(e.getMessage().contains("未知RESP类型"));
			}

			// 中间损坏无法安全推断边界，因此不能自动删除或跳过任何字节。
			Assert.assertArrayEquals(originalBytes, Files.readAllBytes(path));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 将字符串参数构造成RESP数组命令。
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

	/**
	 * 从RedisCore读取指定key对应的字符串内容。
	 *
	 * @param redisCore Redis核心存储
	 * @param key 需要读取的key
	 * @return UTF-8字符串内容
	 */
	private String stringValue(RedisCore redisCore, String key) {
		RedisData redisData = redisCore.get(new BytesWrapper(
				key.getBytes(StandardCharsets.UTF_8)
		));

		Assert.assertTrue(redisData instanceof RedisString);
		return ((RedisString) redisData).getValue().toUtf8String();
	}
}
