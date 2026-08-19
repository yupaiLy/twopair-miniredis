package cn.twopair.command.impl.set;

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
import java.util.List;

/**
 * @author ljj
 * @description 测试SREM命令的参数解析、批量成员删除和返回值。
 * @date 2026/8/19
 * @twopair
 */
public class SRemTest {

	/**
	 * 验证SREM只统计实际删除的成员，并在Set为空后删除整个key。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("java"), bytes("redis"), bytes("中文")));
		SRem firstCommand = new SRem();
		firstCommand.setContent(new Resp[]{bulk("SREM"), bulk("tags"), bulk("redis"), bulk("missing"), bulk("redis")});
		SRem secondCommand = new SRem();
		secondCommand.setContent(new Resp[]{bulk("SREM"), bulk("tags"), bulk("java"), bulk("中文")});

		RespInt firstResponse = (RespInt) firstCommand.handle(redisCore);

		Assert.assertEquals(CommandType.SREM, firstCommand.type());
		Assert.assertEquals(1L, firstResponse.getValue());
		Assert.assertNotNull(redisCore.get(bytes("tags")));

		RespInt secondResponse = (RespInt) secondCommand.handle(redisCore);

		Assert.assertEquals(2L, secondResponse.getValue());
		Assert.assertNull(redisCore.get(bytes("tags")));
	}

	/**
	 * 验证SREM拒绝参数不足、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("SREM"), bulk("tags")}, "SREM命令需要key和至少一个member参数");
		assertInvalid(new Resp[]{bulk("SREM"), new RespInt(1L), bulk("java")}, "SREM的key和member必须是BulkString");
		assertInvalid(new Resp[]{bulk("SREM"), BulkString.NIL, bulk("java")}, "SREM的key不能是NIL");
		assertInvalid(new Resp[]{bulk("SREM"), bulk("tags"), BulkString.NIL}, "SREM的member不能是NIL");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			SRem command = new SRem();
			command.setContent(array);
			Assert.fail("非法SREM参数应抛出IllegalArgumentException");
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
