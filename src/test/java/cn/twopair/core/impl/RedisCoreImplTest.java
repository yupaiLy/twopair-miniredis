package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ljj
 * @description
 * @date 2026/4/9
 * @twopair
 */
public class RedisCoreImplTest {
	/**
	 * @author ljj
	 * @description 测试通过可控时间完成 TTL 惰性删除，不使用 Thread.sleep。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testLazyExpirationWithoutSleep() {
		// 使用 AtomicLong 模拟当前时间。
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore core = new RedisCoreImpl(currentTime::get);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);
		RedisString value = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);

		// 绝对时间达到 1500ms 时过期。
		value.setTimeout(1500L);
		core.put(key, value);

		Assert.assertNotNull(core.get(key));

		currentTime.set(1499L);
		Assert.assertNotNull(core.get(key));

		// 到达过期时间本身就应视为过期。
		currentTime.set(1500L);
		Assert.assertNull(core.get(key));
		Assert.assertFalse(core.exist(key));
	}
	/**
	 * @author ljj
	 * @description 测试 EXPIRE 的正常过期、立即删除和 key 不存在语义。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testExpire() {
		// 使用可控时间，避免 Thread.sleep 导致测试变慢或不稳定。
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore core = new RedisCoreImpl(currentTime::get);

		BytesWrapper missingKey = new BytesWrapper(
				"missing".getBytes(StandardCharsets.UTF_8)
		);

		// 不存在的 key 无法设置过期时间。
		Assert.assertFalse(core.expire(missingKey, 10L));

		BytesWrapper expiringKey = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);
		RedisString expiringValue = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);
		core.put(expiringKey, expiringValue);

		// 当前时间为 1000ms，10 秒后的绝对过期时间是 11000ms。
		Assert.assertTrue(core.expire(expiringKey, 10L));
		Assert.assertEquals(11000L, expiringValue.timeout());

		// 到达过期时间之前，数据仍然存在。
		currentTime.set(10999L);
		Assert.assertNotNull(core.get(expiringKey));

		// 到达绝对过期时间时，数据应被惰性删除。
		currentTime.set(11000L);
		Assert.assertNull(core.get(expiringKey));
		Assert.assertFalse(core.exist(expiringKey));

		BytesWrapper immediateKey = new BytesWrapper(
				"immediate".getBytes(StandardCharsets.UTF_8)
		);
		RedisString immediateValue = new RedisString(
				new BytesWrapper(
						"delete-now".getBytes(StandardCharsets.UTF_8)
				)
		);
		core.put(immediateKey, immediateValue);

		// 过期秒数为 0，存在的 key 应立即删除并返回 true。
		Assert.assertTrue(core.expire(immediateKey, 0L));
		Assert.assertNull(core.get(immediateKey));

		// 已删除的 key 再次执行 expire，应返回 false。
		Assert.assertFalse(core.expire(immediateKey, 0L));

		BytesWrapper negativeKey = new BytesWrapper(
				"negative".getBytes(StandardCharsets.UTF_8)
		);
		RedisString negativeValue = new RedisString(
				new BytesWrapper(
						"delete-too".getBytes(StandardCharsets.UTF_8)
				)
		);
		core.put(negativeKey, negativeValue);

		// 负数同样属于非正数，应立即删除。
		Assert.assertTrue(core.expire(negativeKey, -1L));
		Assert.assertNull(core.get(negativeKey));
	}
	/**
	 * @author ljj
	 * @description 测试 TTL 对不存在、永久存在和即将过期 key 的返回值。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testTtl() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore core = new RedisCoreImpl(currentTime::get);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		// 不存在的 key 返回 -2。
		Assert.assertEquals(-2L, core.ttl(key));

		RedisString value = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);
		core.put(key, value);

		// timeout 为 -1，表示永久存在。
		Assert.assertEquals(-1L, core.ttl(key));

		// 当前时间为 1000ms，设置 10 秒后过期。
		Assert.assertTrue(core.expire(key, 10L));
		Assert.assertEquals(10L, core.ttl(key));

		// 经过 1 秒，剩余 9 秒。
		currentTime.set(2000L);
		Assert.assertEquals(9L, core.ttl(key));

		// 只剩 1ms 时，整数秒结果为 0，但 key 仍然存在。
		currentTime.set(10999L);
		Assert.assertEquals(0L, core.ttl(key));
		Assert.assertNotNull(core.get(key));

		// 到达过期时间后，key 被视为不存在。
		currentTime.set(11000L);
		Assert.assertEquals(-2L, core.ttl(key));
		Assert.assertNull(core.get(key));
	}
	/**
	 * @author ljj
	 * @description 测试一次性写入数据及其过期时间。
	 * @date 2026/8/11
	 * @twopair
	 */
	@Test
	public void testPutWithExpiration() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);
		RedisString value = new RedisString(
				new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				)
		);

		// 一次操作同时写入value和10秒过期时间。
		redisCore.putWithExpiration(key, value, 10L);

		// 新对象发布到Map时，必须已经带有完整的绝对过期时间。
		Assert.assertSame(value, redisCore.get(key));
		Assert.assertEquals(11000L, value.timeout());
		Assert.assertEquals(10L, redisCore.ttl(key));

		// 到达过期时间前仍然存在。
		currentTime.set(10999L);
		Assert.assertNotNull(redisCore.get(key));

		// 到达绝对过期时间时被惰性删除。
		currentTime.set(11000L);
		Assert.assertNull(redisCore.get(key));
		Assert.assertEquals(-2L, redisCore.ttl(key));
	}

	/**
	 * 验证带过期时间的写入会拒绝零和负数，并且不会写入key或修改传入的value。
	 */
	@Test
	public void testPutWithExpirationRejectsNonPositiveSeconds() {
		RedisCore redisCore = new RedisCoreImpl(() -> 1000L);
		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		RedisString zeroSecondsValue = new RedisString(
				new BytesWrapper("zero".getBytes(StandardCharsets.UTF_8))
		);
		try {
			redisCore.putWithExpiration(key, zeroSecondsValue, 0L);
			Assert.fail("过期时间为0时应抛出IllegalArgumentException");
		} catch (IllegalArgumentException ignored) {
			// 预期异常，继续验证失败操作没有产生副作用。
		}
		Assert.assertNull(redisCore.get(key));
		Assert.assertEquals(-1L, zeroSecondsValue.timeout());

		RedisString negativeSecondsValue = new RedisString(
				new BytesWrapper("negative".getBytes(StandardCharsets.UTF_8))
		);
		try {
			redisCore.putWithExpiration(key, negativeSecondsValue, -1L);
			Assert.fail("过期时间为负数时应抛出IllegalArgumentException");
		} catch (IllegalArgumentException ignored) {
			// 预期异常，继续验证失败操作没有产生副作用。
		}
		Assert.assertNull(redisCore.get(key));
		Assert.assertEquals(-1L, negativeSecondsValue.timeout());
	}

	/**
	 * 验证主动清理只删除已过期数据，并保留永久数据和未过期数据。
	 */
	@Test
	public void testRemoveExpired() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		BytesWrapper expiredKey = new BytesWrapper(
				"expired".getBytes(StandardCharsets.UTF_8)
		);
		RedisString expiredValue = new RedisString(
				new BytesWrapper("old".getBytes(StandardCharsets.UTF_8))
		);
		expiredValue.setTimeout(1000L);
		redisCore.put(expiredKey, expiredValue);

		BytesWrapper aliveKey = new BytesWrapper(
				"alive".getBytes(StandardCharsets.UTF_8)
		);
		RedisString aliveValue = new RedisString(
				new BytesWrapper("new".getBytes(StandardCharsets.UTF_8))
		);
		aliveValue.setTimeout(2000L);
		redisCore.put(aliveKey, aliveValue);

		BytesWrapper permanentKey = new BytesWrapper(
				"permanent".getBytes(StandardCharsets.UTF_8)
		);
		RedisString permanentValue = new RedisString(
				new BytesWrapper("forever".getBytes(StandardCharsets.UTF_8))
		);
		redisCore.put(permanentKey, permanentValue);

		Assert.assertEquals(1, redisCore.removeExpired());
		Assert.assertNull(redisCore.get(expiredKey));
		Assert.assertSame(aliveValue, redisCore.get(aliveKey));
		Assert.assertSame(permanentValue, redisCore.get(permanentKey));

		currentTime.set(2000L);
		Assert.assertEquals(1, redisCore.removeExpired());
		Assert.assertNull(redisCore.get(aliveKey));
		Assert.assertEquals(0, redisCore.removeExpired());
	}
}
