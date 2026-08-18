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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试PEXPIREAT绝对过期时间命令。
 */
public class PExpireAtTest {

	/**
	 * 验证PEXPIREAT使用绝对毫秒时间设置过期点。
	 */
	@Test
	public void testExpireAtAbsoluteMillis() {
		AtomicLong currentTime = new AtomicLong(5000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("name");
		redisCore.put(key, new RedisString(bytes("twopair")));

		PExpireAt command = new PExpireAt();
		command.setContent(new Resp[]{
				new BulkString(bytes("PEXPIREAT")),
				new BulkString(key),
				new BulkString(bytes("11000"))
		});

		Assert.assertEquals(CommandType.PEXPIREAT, command.type());
		RespInt response = (RespInt) command.handle(redisCore);
		Assert.assertEquals(1L, response.getValue());
		Assert.assertEquals(6L, redisCore.ttl(key));

		currentTime.set(11000L);
		Assert.assertNull(redisCore.get(key));
	}

	/**
	 * 将字符串转换成UTF-8字节包装器。
	 *
	 * @param value 字符串内容
	 * @return 字节包装器
	 */
	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
