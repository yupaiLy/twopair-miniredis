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
 * @description EXPIRE命令测试
 * @date 2026/7/16
 * @twopair
 */
public class ExpireTest {

	/**
	 * @author ljj
	 * @description 测试EXPIRE的返回值、过期效果和非法数字参数。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testExpire() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		Expire expire = new Expire();
		expire.setContent(new Resp[]{
				new BulkString(new BytesWrapper(
						"EXPIRE".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(key),
				new BulkString(new BytesWrapper(
						"10".getBytes(StandardCharsets.UTF_8)
				))
		});

		Assert.assertEquals(CommandType.EXPIRE, expire.type());

		// key 不存在时返回 0。
		RespInt missingResponse = (RespInt) expire.handle(redisCore);
		Assert.assertEquals(0L, missingResponse.getValue());

		RedisString value = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);
		redisCore.put(key, value);

		// key 存在时设置过期时间并返回 1。
		RespInt successResponse = (RespInt) expire.handle(redisCore);
		Assert.assertEquals(1L, successResponse.getValue());
		Assert.assertEquals(10L, redisCore.ttl(key));

		currentTime.set(11000L);
		Assert.assertNull(redisCore.get(key));

		// seconds 不是整数时必须抛出明确的参数异常。
		try {
			Expire invalidExpire = new Expire();
			invalidExpire.setContent(new Resp[]{
					new BulkString(new BytesWrapper(
							"EXPIRE".getBytes(StandardCharsets.UTF_8)
					)),
					new BulkString(key),
					new BulkString(new BytesWrapper(
							"abc".getBytes(StandardCharsets.UTF_8)
					))
			});
			Assert.fail("预期非法seconds应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(
					"EXPIRE的seconds必须是整数",
					e.getMessage()
			);
		}
	}
}