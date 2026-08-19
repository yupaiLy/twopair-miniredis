package cn.twopair.command.impl.set;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisSet;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试SADD命令的参数解析、成员去重和新增数量响应。
 * @date 2026/8/19
 * @twopair
 */
public class SAddTest {

	/**
	 * 验证SADD支持多个成员，并且重复成员不计入返回值。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		SAdd firstCommand = new SAdd();
		firstCommand.setContent(new Resp[]{bulk("SADD"), bulk("tags"), bulk("java"), bulk("redis"), bulk("中文"), bulk("java")});
		SAdd secondCommand = new SAdd();
		secondCommand.setContent(new Resp[]{bulk("SADD"), bulk("tags"), bulk("redis"), bulk("netty")});

		RespInt firstResponse = (RespInt) firstCommand.handle(redisCore);
		RespInt secondResponse = (RespInt) secondCommand.handle(redisCore);

		Assert.assertEquals(CommandType.SADD, firstCommand.type());
		Assert.assertEquals(3L, firstResponse.getValue());
		Assert.assertEquals(1L, secondResponse.getValue());
		RedisSet redisSet = (RedisSet) redisCore.get(bytes("tags"));
		Assert.assertTrue(redisSet.contains(bytes("java")));
		Assert.assertTrue(redisSet.contains(bytes("中文")));
		Assert.assertTrue(redisSet.contains(bytes("netty")));
		Assert.assertEquals(4L, redisSet.size());
	}

	/**
	 * 验证SADD拒绝参数不足、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("SADD"), bulk("tags")}, "SADD命令需要key和至少一个member参数");
		assertInvalid(new Resp[]{bulk("SADD"), new RespInt(1L), bulk("java")}, "SADD的key和member必须是BulkString");
		assertInvalid(new Resp[]{bulk("SADD"), BulkString.NIL, bulk("java")}, "SADD的key不能是NIL");
		assertInvalid(new Resp[]{bulk("SADD"), bulk("tags"), BulkString.NIL}, "SADD的member不能是NIL");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			SAdd command = new SAdd();
			command.setContent(array);
			Assert.fail("非法SADD参数应抛出IllegalArgumentException");
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
