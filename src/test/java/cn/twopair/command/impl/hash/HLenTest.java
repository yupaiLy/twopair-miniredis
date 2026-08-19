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
 * @description 测试HLEN命令的参数解析和Hash字段数量响应。
 * @date 2026/8/19
 * @twopair
 */
public class HLenTest {

	/**
	 * 验证HLEN返回Hash字段数量，不存在的key返回0。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.putHashFields(bytes("user:1"), Map.of(bytes("name"), bytes("老板"), bytes("city"), bytes("杭州")));
		HLen existingCommand = command("user:1");
		HLen missingCommand = command("missing");

		RespInt existingResponse = (RespInt) existingCommand.handle(redisCore);
		RespInt missingResponse = (RespInt) missingCommand.handle(redisCore);

		Assert.assertEquals(CommandType.HLEN, existingCommand.type());
		Assert.assertEquals(2L, existingResponse.getValue());
		Assert.assertEquals(0L, missingResponse.getValue());
	}

	/**
	 * 验证HLEN拒绝参数数量错误、错误RESP类型和NIL key。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HLEN")}, "HLEN命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("HLEN"), bulk("user:1"), bulk("extra")}, "HLEN命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("HLEN"), new RespInt(1L)}, "HLEN的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("HLEN"), BulkString.NIL}, "HLEN的key不能是NIL");
	}

	private HLen command(String key) {
		HLen command = new HLen();
		command.setContent(new Resp[]{bulk("HLEN"), bulk(key)});
		return command;
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			HLen command = new HLen();
			command.setContent(array);
			Assert.fail("非法HLEN参数应抛出IllegalArgumentException");
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
