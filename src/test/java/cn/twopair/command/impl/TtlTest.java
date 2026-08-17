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
 * @author ljj
 * @description TTL命令测试
 * @date 2026/7/16
 * @twopair
 */
public class TtlTest {

	/**
	 * @author ljj
	 * @description 测试TTL命令对不存在、永久存在和即将过期key的响应。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testTtl() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		Ttl ttl = new Ttl();
		ttl.setContent(new Resp[]{
				new BulkString(new BytesWrapper(
						"TTL".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(key)
		});

		Assert.assertEquals(CommandType.TTL, ttl.type());

		// key不存在时返回-2。
		RespInt missingResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(-2L, missingResponse.getValue());

		RedisString value = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);
		redisCore.put(key, value);

		// 永久存在的key返回-1。
		RespInt persistentResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(-1L, persistentResponse.getValue());

		// 设置10秒过期时间。
		Assert.assertTrue(redisCore.expire(key, 10L));

		RespInt initialResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(10L, initialResponse.getValue());

		// 经过1秒后还剩9秒。
		currentTime.set(2000L);
		RespInt remainingResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(9L, remainingResponse.getValue());

		// 剩余不足1秒时返回0，但key仍然存在。
		currentTime.set(10999L);
		RespInt zeroResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(0L, zeroResponse.getValue());
		Assert.assertNotNull(redisCore.get(key));

		// 到达过期时间后返回-2。
		currentTime.set(11000L);
		RespInt expiredResponse = (RespInt) ttl.handle(redisCore);
		Assert.assertEquals(-2L, expiredResponse.getValue());

		// TTL命令缺少key时必须抛出明确的参数异常。
		try {
			Ttl invalidTtl = new Ttl();
			invalidTtl.setContent(new Resp[]{
					new BulkString(new BytesWrapper(
							"TTL".getBytes(StandardCharsets.UTF_8)
					))
			});
			Assert.fail("预期缺少key应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(
					"TTL命令需要key一个参数",
					e.getMessage()
			);
		}
	}
}
