package cn.twopair.persistence.aof;

import cn.twopair.command.CommandFactory;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 验证AOF Rewrite恢复命令的生成规则。
 *
 * @author ljj
 */
public class AofRewriteCommandBuilderTest {

	/**
	 * 验证String数据按照key字节顺序生成SET命令，并保持UTF-8内容不变。
	 */
	@Test
	public void testBuildStringCommandsInKeyOrder() {
		RedisCoreImpl redisCore = new RedisCoreImpl();
		redisCore.put(bytes("姓名"), new RedisString(bytes("李")));
		redisCore.put(bytes("age"), new RedisString(bytes("18")));

		List<RespArray> commands = AofRewriteCommandBuilder.build(redisCore);

		Assert.assertEquals(2, commands.size());
		assertCommand(commands.get(0), "SET", "age", "18");
		assertCommand(commands.get(1), "SET", "姓名", "李");
	}

	/**
	 * 验证带过期时间的String在SET之后生成绝对毫秒PEXPIREAT命令。
	 */
	@Test
	public void testBuildAbsoluteExpirationAfterSet() {
		RedisCoreImpl redisCore = new RedisCoreImpl(() -> 1000L);
		redisCore.putWithExpiration(bytes("session"), new RedisString(bytes("token")), 10L);

		List<RespArray> commands = AofRewriteCommandBuilder.build(redisCore);

		Assert.assertEquals(2, commands.size());
		assertCommand(commands.get(0), "SET", "session", "token");
		assertCommand(commands.get(1), "PEXPIREAT", "session", "11000");
	}

	/**
	 * 验证生成Rewrite命令时不会包含已经过期的数据。
	 */
	@Test
	public void testIgnoreExpiredString() {
		AtomicLong currentTimeMillis = new AtomicLong(1000L);
		RedisCoreImpl redisCore = new RedisCoreImpl(currentTimeMillis::get);
		redisCore.putWithExpiration(bytes("expired"), new RedisString(bytes("value")), 1L);
		currentTimeMillis.set(2000L);

		List<RespArray> commands = AofRewriteCommandBuilder.build(redisCore);

		Assert.assertTrue(commands.isEmpty());
		Assert.assertFalse(redisCore.exist(bytes("expired")));
	}

	/**
	 * 验证生成的命令拥有独立字节快照，不受原始key和value修改影响。
	 */
	@Test
	public void testCopyStringCommandBytes() {
		byte[] keyBytes = new byte[]{0, 1, 2};
		byte[] valueBytes = new byte[]{3, 4, 5};
		RedisCoreImpl redisCore = new RedisCoreImpl();
		redisCore.put(new BytesWrapper(keyBytes), new RedisString(new BytesWrapper(valueBytes)));

		RespArray command = AofRewriteCommandBuilder.build(redisCore).get(0);
		keyBytes[0] = 9;
		valueBytes[0] = 9;
		Resp[] arguments = command.getArray();

		Assert.assertArrayEquals(new byte[]{0, 1, 2}, ((BulkString) arguments[1]).getBytesWrapper().getByteArray());
		Assert.assertArrayEquals(new byte[]{3, 4, 5}, ((BulkString) arguments[2]).getBytesWrapper().getByteArray());
	}

	/**
	 * 验证List按照头到尾顺序生成RPUSH命令，并在重放后保持列表顺序和过期时间。
	 */
	@Test
	public void testBuildAndReplayListCommands() {
		RedisCoreImpl source = new RedisCoreImpl(() -> 1000L);
		source.rightPush(bytes("letters"), List.of(bytes("one"), bytes("two"), bytes("三")));
		source.expireAt(bytes("letters"), 11000L);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		assertCommand(commands.get(0), "RPUSH", "letters", "one", "two", "三");
		assertCommand(commands.get(1), "PEXPIREAT", "letters", "11000");

		RedisCoreImpl restored = new RedisCoreImpl(() -> 1000L);
		for (RespArray command : commands) {
			CommandFactory.from(command).handle(restored);
		}

		Assert.assertEquals(List.of("one", "two", "三"), toText(restored.listRange(bytes("letters"), 0L, -1L)));
		Assert.assertEquals(10L, restored.ttl(bytes("letters")));
	}

	/**
	 * 验证65个列表元素按照Redis规则拆分为64个和1个元素的两条RPUSH命令。
	 */
	@Test
	public void testSplitListCommandsEvery64Items() {
		RedisCoreImpl source = new RedisCoreImpl();
		List<BytesWrapper> expectedElements = new java.util.ArrayList<>();

		for (int i = 0; i < 65; i++) {
			expectedElements.add(bytes("value-" + i));
		}
		source.rightPush(bytes("numbers"), expectedElements);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		Assert.assertEquals(66, commands.get(0).getArray().length);
		Assert.assertEquals(3, commands.get(1).getArray().length);
		assertBulkString(commands.get(0).getArray()[0], "RPUSH");
		assertBulkString(commands.get(0).getArray()[2], "value-0");
		assertBulkString(commands.get(0).getArray()[65], "value-63");
		assertBulkString(commands.get(1).getArray()[2], "value-64");

		RedisCoreImpl restored = new RedisCoreImpl();
		for (RespArray command : commands) {
			CommandFactory.from(command).handle(restored);
		}

		Assert.assertEquals(toText(expectedElements), toText(restored.listRange(bytes("numbers"), 0L, -1L)));
	}

	/**
	 * 验证Hash按照field字节顺序生成HMSET，并在重放后保持字段、中文内容和过期时间。
	 */
	@Test
	public void testBuildAndReplayHashCommands() {
		RedisCoreImpl source = new RedisCoreImpl(() -> 1000L);
		Map<BytesWrapper, BytesWrapper> fields = new LinkedHashMap<>();
		fields.put(bytes("name"), bytes("老板"));
		fields.put(bytes("city"), bytes("杭州"));
		fields.put(bytes("年龄"), bytes("18"));
		source.putHashFields(bytes("user:1"), fields);
		source.expireAt(bytes("user:1"), 11000L);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		assertCommand(commands.get(0), "HMSET", "user:1", "city", "杭州", "name", "老板", "年龄", "18");
		assertCommand(commands.get(1), "PEXPIREAT", "user:1", "11000");

		RedisCoreImpl restored = new RedisCoreImpl(() -> 1000L);
		replay(commands, restored);

		Assert.assertEquals(3L, restored.getHashSize(bytes("user:1")));
		Assert.assertEquals("老板", restored.getHashField(bytes("user:1"), bytes("name")).toUtf8String());
		Assert.assertEquals("杭州", restored.getHashField(bytes("user:1"), bytes("city")).toUtf8String());
		Assert.assertEquals("18", restored.getHashField(bytes("user:1"), bytes("年龄")).toUtf8String());
		Assert.assertEquals(10L, restored.ttl(bytes("user:1")));
	}

	/**
	 * 验证65个Hash条目按照Redis规则拆分为64个和1个条目的两条HMSET命令。
	 */
	@Test
	public void testSplitHashCommandsEvery64Items() {
		RedisCoreImpl source = new RedisCoreImpl();
		Map<BytesWrapper, BytesWrapper> fields = new LinkedHashMap<>();

		for (int i = 0; i < 65; i++) {
			fields.put(bytes(String.format("field-%02d", i)), bytes(String.format("value-%02d", i)));
		}
		source.putHashFields(bytes("user"), fields);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		Assert.assertEquals(130, commands.get(0).getArray().length);
		Assert.assertEquals(4, commands.get(1).getArray().length);
		assertBulkString(commands.get(0).getArray()[0], "HMSET");
		assertBulkString(commands.get(0).getArray()[2], "field-00");
		assertBulkString(commands.get(0).getArray()[128], "field-63");
		assertBulkString(commands.get(1).getArray()[2], "field-64");

		RedisCoreImpl restored = new RedisCoreImpl();
		replay(commands, restored);

		Assert.assertEquals(65L, restored.getHashSize(bytes("user")));
		for (int i = 0; i < 65; i++) {
			String field = String.format("field-%02d", i);
			String expectedValue = String.format("value-%02d", i);
			Assert.assertEquals(expectedValue, restored.getHashField(bytes("user"), bytes(field)).toUtf8String());
		}
	}

	/**
	 * 验证Set按照成员字节顺序生成SADD，并在重放后保持成员、中文内容和过期时间。
	 */
	@Test
	public void testBuildAndReplaySetCommands() {
		RedisCoreImpl source = new RedisCoreImpl(() -> 1000L);
		source.addSetMembers(bytes("tags"), List.of(bytes("中文"), bytes("redis"), bytes("java")));
		source.expireAt(bytes("tags"), 11000L);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		assertCommand(commands.get(0), "SADD", "tags", "java", "redis", "中文");
		assertCommand(commands.get(1), "PEXPIREAT", "tags", "11000");

		RedisCoreImpl restored = new RedisCoreImpl(() -> 1000L);
		replay(commands, restored);

		Assert.assertEquals(3L, restored.getSetSize(bytes("tags")));
		Assert.assertTrue(restored.containsSetMember(bytes("tags"), bytes("java")));
		Assert.assertTrue(restored.containsSetMember(bytes("tags"), bytes("redis")));
		Assert.assertTrue(restored.containsSetMember(bytes("tags"), bytes("中文")));
		Assert.assertEquals(10L, restored.ttl(bytes("tags")));
	}

	/**
	 * 验证65个Set成员按照Redis规则拆分为64个和1个成员的两条SADD命令。
	 */
	@Test
	public void testSplitSetCommandsEvery64Items() {
		RedisCoreImpl source = new RedisCoreImpl();
		List<BytesWrapper> members = new java.util.ArrayList<>();

		for (int i = 0; i < 65; i++) {
			members.add(bytes(String.format("member-%02d", i)));
		}
		source.addSetMembers(bytes("members"), members);

		List<RespArray> commands = AofRewriteCommandBuilder.build(source);

		Assert.assertEquals(2, commands.size());
		Assert.assertEquals(66, commands.get(0).getArray().length);
		Assert.assertEquals(3, commands.get(1).getArray().length);
		assertBulkString(commands.get(0).getArray()[0], "SADD");
		assertBulkString(commands.get(0).getArray()[2], "member-00");
		assertBulkString(commands.get(0).getArray()[65], "member-63");
		assertBulkString(commands.get(1).getArray()[2], "member-64");

		RedisCoreImpl restored = new RedisCoreImpl();
		replay(commands, restored);

		Assert.assertEquals(65L, restored.getSetSize(bytes("members")));
		for (int i = 0; i < 65; i++) {
			Assert.assertTrue(restored.containsSetMember(bytes("members"), bytes(String.format("member-%02d", i))));
		}
	}

	/**
	 * 校验RESP数组中的BulkString参数。
	 *
	 * @param command AOF恢复命令
	 * @param expectedArguments 期望的UTF-8参数
	 */
	private void assertCommand(RespArray command, String... expectedArguments) {
		Resp[] arguments = command.getArray();
		Assert.assertEquals(expectedArguments.length, arguments.length);

		for (int i = 0; i < expectedArguments.length; i++) {
			Assert.assertTrue(arguments[i] instanceof BulkString);
			Assert.assertEquals(expectedArguments[i], ((BulkString) arguments[i]).getBytesWrapper().toUtf8String());
		}
	}

	/**
	 * 校验单个RESP参数是指定文本的BulkString。
	 *
	 * @param resp RESP参数
	 * @param expected 期望文本
	 */
	private void assertBulkString(Resp resp, String expected) {
		Assert.assertTrue(resp instanceof BulkString);
		Assert.assertEquals(expected, ((BulkString) resp).getBytesWrapper().toUtf8String());
	}

	/**
	 * 将AOF恢复命令依次重放到指定Redis核心存储。
	 *
	 * @param commands AOF恢复命令
	 * @param redisCore 接收恢复数据的Redis核心存储
	 */
	private void replay(List<RespArray> commands, RedisCoreImpl redisCore) {
		for (RespArray command : commands) {
			CommandFactory.from(command).handle(redisCore);
		}
	}

	/**
	 * 将字节包装对象列表转换为UTF-8文本列表。
	 *
	 * @param values 字节包装对象列表
	 * @return UTF-8文本列表
	 */
	private List<String> toText(List<BytesWrapper> values) {
		List<String> result = new java.util.ArrayList<>(values.size());
		for (BytesWrapper value : values) {
			result.add(value.toUtf8String());
		}
		return result;
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
}
