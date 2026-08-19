package cn.twopair.command.impl.hash;

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
import java.util.Map;

/**
 * @author ljj
 * @description 测试HSCAN命令的分页、过滤、空结果和类型检查。
 * @date 2026/8/19
 * @twopair
 */
public class HScanTest {

	/**
	 * 验证HSCAN按field匹配并以field、value交替格式分页返回。
	 */
	@Test
	public void testHandleWithMatchAndCount() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.putHashFields(bytes("user"), Map.of(
				bytes("name"), bytes("twopair"),
				bytes("nickname"), bytes("pair"),
				bytes("age"), bytes("18")
		));

		HScan firstCommand = command("user", "0", "MATCH", "n*", "COUNT", "1");
		RespArray firstResponse = (RespArray) firstCommand.handle(redisCore);
		Assert.assertEquals(CommandType.HSCAN, firstCommand.type());
		assertResponse(firstResponse, "1", "name", "twopair");

		HScan secondCommand = command("user", "1", "MATCH", "n*", "COUNT", "1");
		RespArray secondResponse = (RespArray) secondCommand.handle(redisCore);
		assertResponse(secondResponse, "0", "nickname", "pair");
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
			Assert.fail("对String执行HSCAN时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}
	}

	/**
	 * 验证HSCAN拒绝缺少参数和非法游标。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HSCAN"), bulk("user")}, "HSCAN命令需要key和cursor参数");
		assertInvalid(new Resp[]{bulk("HSCAN"), bulk("user"), bulk("-1")}, "HSCAN的cursor必须是非负整数");
		assertInvalid(new Resp[]{bulk("HSCAN"), bulk("user"), bulk("0"), bulk("COUNT")}, "HSCAN选项必须成对出现");
	}

	private HScan command(String... arguments) {
		Resp[] array = new Resp[arguments.length + 1];
		array[0] = bulk("HSCAN");
		for (int i = 0; i < arguments.length; i++) {
			array[i + 1] = bulk(arguments[i]);
		}
		HScan command = new HScan();
		command.setContent(array);
		return command;
	}

	private void assertResponse(RespArray response, String cursor, String... values) {
		Assert.assertEquals(cursor, text(response.getArray()[0]));
		Resp[] items = ((RespArray) response.getArray()[1]).getArray();
		Assert.assertEquals(values.length, items.length);
		for (int i = 0; i < values.length; i++) {
			Assert.assertEquals(values[i], text(items[i]));
		}
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			HScan command = new HScan();
			command.setContent(array);
			Assert.fail("非法HSCAN参数应抛出IllegalArgumentException");
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
