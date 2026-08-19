package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试DEL命令的参数解析、批量key删除和返回值。
 * @date 2026/8/20
 * @twopair
 */
public class DelTest {

	/**
	 * 验证DEL只统计实际删除的key。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));
		redisCore.put(bytes("city"), new RedisString(bytes("杭州")));
		Del firstCommand = new Del();
		firstCommand.setContent(new Resp[]{bulk("DEL"), bulk("name"), bulk("missing"), bulk("city")});
		Del secondCommand = new Del();
		secondCommand.setContent(new Resp[]{bulk("DEL"), bulk("name")});

		RespInt firstResponse = (RespInt) firstCommand.handle(redisCore);

		Assert.assertEquals(CommandType.DEL, firstCommand.type());
		Assert.assertEquals(2L, firstResponse.getValue());
		Assert.assertNull(redisCore.get(bytes("name")));
		Assert.assertNull(redisCore.get(bytes("city")));

		RespInt secondResponse = (RespInt) secondCommand.handle(redisCore);

		Assert.assertEquals(0L, secondResponse.getValue());
	}

	/**
	 * 验证DEL拒绝参数不足、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("DEL")}, "DEL命令需要至少一个key参数");
		assertInvalid(new Resp[]{bulk("DEL"), new RespInt(1L)}, "DEL的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("DEL"), BulkString.NIL}, "DEL的key不能是NIL");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			Del command = new Del();
			command.setContent(array);
			Assert.fail("非法DEL参数应抛出IllegalArgumentException");
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