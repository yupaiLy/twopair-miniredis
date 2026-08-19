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
 * @description 测试HDEL命令的参数解析、批量字段删除和返回值。
 * @date 2026/8/19
 * @twopair
 */
public class HDelTest {

	/**
	 * 验证HDEL只统计实际删除的字段，并在Hash为空后删除整个key。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.hashSet(bytes("user:1"), Map.of(bytes("name"), bytes("老板"), bytes("city"), bytes("杭州"), bytes("age"), bytes("18")));
		HDel firstCommand = new HDel();
		firstCommand.setContent(new Resp[]{bulk("HDEL"), bulk("user:1"), bulk("city"), bulk("missing"), bulk("city")});
		HDel secondCommand = new HDel();
		secondCommand.setContent(new Resp[]{bulk("HDEL"), bulk("user:1"), bulk("name"), bulk("age")});

		RespInt firstResponse = (RespInt) firstCommand.handle(redisCore);

		Assert.assertEquals(CommandType.HDEL, firstCommand.type());
		Assert.assertEquals(1L, firstResponse.getValue());
		Assert.assertNotNull(redisCore.get(bytes("user:1")));

		RespInt secondResponse = (RespInt) secondCommand.handle(redisCore);

		Assert.assertEquals(2L, secondResponse.getValue());
		Assert.assertNull(redisCore.get(bytes("user:1")));
	}

	/**
	 * 验证HDEL拒绝参数不足、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HDEL"), bulk("user:1")}, "HDEL命令需要key和至少一个field参数");
		assertInvalid(new Resp[]{bulk("HDEL"), new RespInt(1L), bulk("name")}, "HDEL的key和field必须是BulkString");
		assertInvalid(new Resp[]{bulk("HDEL"), BulkString.NIL, bulk("name")}, "HDEL的key不能是NIL");
		assertInvalid(new Resp[]{bulk("HDEL"), bulk("user:1"), BulkString.NIL}, "HDEL的field不能是NIL");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			HDel command = new HDel();
			command.setContent(array);
			Assert.fail("非法HDEL参数应抛出IllegalArgumentException");
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
