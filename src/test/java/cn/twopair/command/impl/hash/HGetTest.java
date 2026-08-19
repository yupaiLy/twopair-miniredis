package cn.twopair.command.impl.hash;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @author ljj
 * @description 测试HGET命令的参数解析、字段读取和NIL响应。
 * @date 2026/8/19
 * @twopair
 */
public class HGetTest {

	/**
	 * 验证HGET返回字段值，不存在的key或field返回NIL。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.hashSet(bytes("user:1"), Map.of(bytes("name"), bytes("老板")));
		HGet existingCommand = command("user:1", "name");
		HGet missingFieldCommand = command("user:1", "missing");
		HGet missingKeyCommand = command("missing", "name");

		BulkString existingResponse = (BulkString) existingCommand.handle(redisCore);
		BulkString missingFieldResponse = (BulkString) missingFieldCommand.handle(redisCore);
		BulkString missingKeyResponse = (BulkString) missingKeyCommand.handle(redisCore);

		Assert.assertEquals(CommandType.HGET, existingCommand.type());
		Assert.assertEquals("老板", existingResponse.getBytesWrapper().toUtf8String());
		Assert.assertSame(BulkString.NIL, missingFieldResponse);
		Assert.assertSame(BulkString.NIL, missingKeyResponse);
	}

	/**
	 * 验证HGET拒绝参数数量错误、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HGET"), bulk("user:1")}, "HGET命令需要key和field两个参数");
		assertInvalid(new Resp[]{bulk("HGET"), bulk("user:1"), bulk("name"), bulk("extra")}, "HGET命令需要key和field两个参数");
		assertInvalid(new Resp[]{bulk("HGET"), new RespInt(1L), bulk("name")}, "HGET的key和field必须是BulkString");
		assertInvalid(new Resp[]{bulk("HGET"), BulkString.NIL, bulk("name")}, "HGET的key和field不能是NIL");
		assertInvalid(new Resp[]{bulk("HGET"), bulk("user:1"), BulkString.NIL}, "HGET的key和field不能是NIL");
	}

	private HGet command(String key, String field) {
		HGet command = new HGet();
		command.setContent(new Resp[]{bulk("HGET"), bulk(key), bulk(field)});
		return command;
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			HGet command = new HGet();
			command.setContent(array);
			Assert.fail("非法HGET参数应抛出IllegalArgumentException");
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
