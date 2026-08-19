package cn.twopair.command.impl.set;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author ljj
 * @description 测试SSCAN命令的分页、过滤、空结果和类型检查。
 * @date 2026/8/19
 * @twopair
 */
public class SScanTest {

	/**
	 * 验证SSCAN按成员匹配并根据COUNT分页返回。
	 */
	@Test
	public void testHandleWithMatchAndCount() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("redis"), bytes("javascript"), bytes("java")));

		SScan firstCommand = command("tags", "0", "MATCH", "java*", "COUNT", "1");
		RespArray firstResponse = (RespArray) firstCommand.handle(redisCore);
		Assert.assertEquals(CommandType.SSCAN, firstCommand.type());
		assertResponse(firstResponse, "1", "java");

		SScan secondCommand = command("tags", "1", "MATCH", "java*", "COUNT", "1");
		RespArray secondResponse = (RespArray) secondCommand.handle(redisCore);
		assertResponse(secondResponse, "0", "javascript");
	}

	/**
	 * 验证不存在的key返回空结果，错误类型返回WRONGTYPE。
	 */
	@Test
	public void testMissingAndWrongType() {
		RedisCore redisCore = new RedisCoreImpl();
		assertResponse((RespArray) command("missing", "0").handle(redisCore), "0");

		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));
		try {
			command("name", "0").handle(redisCore);
			Assert.fail("对String执行SSCAN时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}
	}

	/**
	 * 验证SSCAN拒绝非法COUNT和不支持的选项。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("SSCAN"), bulk("tags"), bulk("0"), bulk("COUNT"), bulk("0")}, "SSCAN的COUNT必须是正整数");
		assertInvalid(new Resp[]{bulk("SSCAN"), bulk("tags"), bulk("0"), bulk("TYPE"), bulk("set")}, "不支持的SSCAN选项: TYPE");
	}

	private SScan command(String... arguments) {
		Resp[] array = new Resp[arguments.length + 1];
		array[0] = bulk("SSCAN");
		for (int i = 0; i < arguments.length; i++) {
			array[i + 1] = bulk(arguments[i]);
		}
		SScan command = new SScan();
		command.setContent(array);
		return command;
	}

	private void assertResponse(RespArray response, String cursor, String... members) {
		Assert.assertEquals(cursor, text(response.getArray()[0]));
		Resp[] items = ((RespArray) response.getArray()[1]).getArray();
		Assert.assertEquals(members.length, items.length);
		for (int i = 0; i < members.length; i++) {
			Assert.assertEquals(members[i], text(items[i]));
		}
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			SScan command = new SScan();
			command.setContent(array);
			Assert.fail("非法SSCAN参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(message, e.getMessage());
		}
	}

	private String text(Resp resp) {
		return ((BulkString) resp).getBytesWrapper().toUtf8String();
	}

	private BulkString bulk(String value) {
		return new BulkString(bytes(value));
	}

	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
