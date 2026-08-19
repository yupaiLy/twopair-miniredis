package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import cn.twopair.resp.SimpleString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ljj
 * @description 测试TYPE命令的数据类型识别和参数校验。
 * @date 2026/8/19
 * @twopair
 */
public class TypeTest {

	/**
	 * 验证TYPE能够识别当前项目支持的数据类型和不存在的key。
	 */
	@Test
	public void testHandleSupportedTypes() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));
		redisCore.leftPush(bytes("queue"), List.of(bytes("task")));
		redisCore.putHashFields(bytes("user"), Map.of(bytes("name"), bytes("twopair")));
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("java")));

		Assert.assertEquals(CommandType.TYPE, command("name").type());
		Assert.assertEquals("string", result(command("name"), redisCore));
		Assert.assertEquals("list", result(command("queue"), redisCore));
		Assert.assertEquals("hash", result(command("user"), redisCore));
		Assert.assertEquals("set", result(command("tags"), redisCore));
		Assert.assertEquals("none", result(command("missing"), redisCore));
	}

	/**
	 * 验证TYPE把已经过期的key视为不存在。
	 */
	@Test
	public void testHandleExpiredKey() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		RedisString value = new RedisString(bytes("old"));
		value.setTimeout(1000L);
		redisCore.put(bytes("expired"), value);

		Assert.assertEquals("none", result(command("expired"), redisCore));
		Assert.assertNull(redisCore.get(bytes("expired")));
	}

	/**
	 * 验证TYPE拒绝参数数量、参数类型和NIL key错误。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("TYPE")}, "TYPE命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("TYPE"), new RespInt(1L)}, "TYPE的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("TYPE"), BulkString.NIL}, "TYPE的key不能是NIL");
	}

	private Type command(String key) {
		Type command = new Type();
		command.setContent(new Resp[]{bulk("TYPE"), bulk(key)});
		return command;
	}

	private String result(Type command, RedisCore redisCore) {
		return ((SimpleString) command.handle(redisCore)).getContent();
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			Type command = new Type();
			command.setContent(array);
			Assert.fail("非法TYPE参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(message, e.getMessage());
		}
	}

	private BulkString bulk(String value) {
		return new BulkString(bytes(value));
	}

	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
