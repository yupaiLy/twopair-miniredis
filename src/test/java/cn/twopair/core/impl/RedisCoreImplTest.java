package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisHash;
import cn.twopair.datatype.RedisList;
import cn.twopair.datatype.RedisSet;
import cn.twopair.datatype.RedisString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/**
	 * 验证LPUSH核心操作能够原子创建列表、追加元素、检查类型并替换过期列表。
	 */
	@Test
	public void testLeftPush() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("letters");

		Assert.assertEquals(
				3L,
				redisCore.leftPush(
						key,
						List.of(bytes("one"), bytes("two"), bytes("三"))
				)
		);
		Assert.assertEquals(
				4L,
				redisCore.leftPush(key, List.of(bytes("zero")))
		);

		RedisList redisList = (RedisList) redisCore.get(key);
		Assert.assertEquals("zero", redisList.leftPop().toUtf8String());
		Assert.assertEquals("三", redisList.leftPop().toUtf8String());

		BytesWrapper stringKey = bytes("name");
		redisCore.put(stringKey, new RedisString(bytes("twopair")));

		try {
			redisCore.leftPush(stringKey, List.of(bytes("value")));
			Assert.fail("对String执行LPUSH时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals(
					"WRONGTYPE Operation against a key holding the wrong kind of value",
					e.getMessage()
			);
		}

		BytesWrapper expiredKey = bytes("expired-list");
		RedisList expiredList = new RedisList();
		expiredList.leftPush(List.of(bytes("old")));
		expiredList.setTimeout(1000L);
		redisCore.put(expiredKey, expiredList);

		Assert.assertEquals(
				1L,
				redisCore.leftPush(expiredKey, List.of(bytes("new")))
		);
		RedisList newList = (RedisList) redisCore.get(expiredKey);
		Assert.assertNotSame(expiredList, newList);
		Assert.assertEquals(-1L, newList.timeout());
		Assert.assertEquals("new", newList.leftPop().toUtf8String());
	}

	/**
	 * 验证LPOP核心操作能够弹出头部元素，并在列表为空、过期或类型错误时遵循Redis语义。
	 */
	@Test
	public void testLeftPop() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("letters");

		Assert.assertNull(redisCore.leftPop(key));

		redisCore.leftPush(key, List.of(bytes("one"), bytes("two")));
		Assert.assertEquals("two", redisCore.leftPop(key).toUtf8String());
		Assert.assertNotNull(redisCore.get(key));
		Assert.assertEquals("one", redisCore.leftPop(key).toUtf8String());
		Assert.assertNull(redisCore.get(key));

		BytesWrapper stringKey = bytes("name");
		redisCore.put(stringKey, new RedisString(bytes("twopair")));
		try {
			redisCore.leftPop(stringKey);
			Assert.fail("对String执行LPOP时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertNotNull(e);
		}

		BytesWrapper expiredKey = bytes("expired-list");
		RedisList expiredList = new RedisList();
		expiredList.leftPush(List.of(bytes("old")));
		expiredList.setTimeout(1000L);
		redisCore.put(expiredKey, expiredList);

		Assert.assertNull(redisCore.leftPop(expiredKey));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证LLEN核心操作能够读取列表长度，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testListLength() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("letters");

		Assert.assertEquals(0L, redisCore.listLength(key));

		redisCore.leftPush(key, List.of(bytes("one"), bytes("two"), bytes("三")));
		Assert.assertEquals(3L, redisCore.listLength(key));

		BytesWrapper stringKey = bytes("name");
		redisCore.put(stringKey, new RedisString(bytes("twopair")));
		try {
			redisCore.listLength(stringKey);
			Assert.fail("对String执行LLEN时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-list");
		RedisList expiredList = new RedisList();
		expiredList.leftPush(List.of(bytes("old")));
		expiredList.setTimeout(1000L);
		redisCore.put(expiredKey, expiredList);

		Assert.assertEquals(0L, redisCore.listLength(expiredKey));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证LRANGE核心操作能够查询列表范围，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testListRange() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("letters");

		Assert.assertTrue(redisCore.listRange(key, 0L, -1L).isEmpty());

		redisCore.leftPush(key, List.of(bytes("one"), bytes("two"), bytes("三")));
		List<BytesWrapper> range = redisCore.listRange(key, 0L, 1L);
		Assert.assertEquals(2, range.size());
		Assert.assertEquals("三", range.get(0).toUtf8String());
		Assert.assertEquals("two", range.get(1).toUtf8String());

		BytesWrapper stringKey = bytes("name");
		redisCore.put(stringKey, new RedisString(bytes("twopair")));
		try {
			redisCore.listRange(stringKey, 0L, -1L);
			Assert.fail("对String执行LRANGE时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-list");
		RedisList expiredList = new RedisList();
		expiredList.leftPush(List.of(bytes("old")));
		expiredList.setTimeout(1000L);
		redisCore.put(expiredKey, expiredList);

		Assert.assertTrue(redisCore.listRange(expiredKey, 0L, -1L).isEmpty());
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证HSET核心操作能够原子创建Hash、覆盖字段、检查类型并替换过期数据。
	 */
	@Test
	public void testPutHashFields() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("user:1");
		Map<BytesWrapper, BytesWrapper> initialFields = new LinkedHashMap<>();
		initialFields.put(bytes("name"), bytes("twopair"));
		initialFields.put(bytes("city"), bytes("杭州"));

		Assert.assertEquals(2L, redisCore.putHashFields(key, initialFields));
		Assert.assertEquals(0L, redisCore.putHashFields(key, Map.of(bytes("name"), bytes("老板"))));
		Assert.assertEquals(1L, redisCore.putHashFields(key, Map.of(bytes("age"), bytes("18"))));

		RedisHash redisHash = (RedisHash) redisCore.get(key);
		Assert.assertEquals("老板", redisHash.get(bytes("name")).toUtf8String());
		Assert.assertEquals("杭州", redisHash.get(bytes("city")).toUtf8String());
		Assert.assertEquals("18", redisHash.get(bytes("age")).toUtf8String());
		Assert.assertEquals(3L, redisHash.size());

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.putHashFields(stringKey, Map.of(bytes("field"), bytes("value")));
			Assert.fail("对String执行HSET时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-hash");
		RedisHash expiredHash = new RedisHash();
		expiredHash.set(Map.of(bytes("old"), bytes("value")));
		expiredHash.setTimeout(1000L);
		redisCore.put(expiredKey, expiredHash);

		Assert.assertEquals(1L, redisCore.putHashFields(expiredKey, Map.of(bytes("new"), bytes("value"))));
		RedisHash newHash = (RedisHash) redisCore.get(expiredKey);
		Assert.assertNotSame(expiredHash, newHash);
		Assert.assertEquals(-1L, newHash.timeout());
		Assert.assertNull(newHash.get(bytes("old")));
		Assert.assertEquals("value", newHash.get(bytes("new")).toUtf8String());
	}

	/**
	 * 验证HGET核心操作能够读取字段，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testGetHashField() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("user:1");

		Assert.assertNull(redisCore.getHashField(key, bytes("name")));

		redisCore.putHashFields(key, Map.of(bytes("name"), bytes("老板")));
		Assert.assertEquals("老板", redisCore.getHashField(key, bytes("name")).toUtf8String());
		Assert.assertNull(redisCore.getHashField(key, bytes("missing")));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.getHashField(stringKey, bytes("field"));
			Assert.fail("对String执行HGET时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-hash");
		RedisHash expiredHash = new RedisHash();
		expiredHash.set(Map.of(bytes("name"), bytes("old")));
		expiredHash.setTimeout(1000L);
		redisCore.put(expiredKey, expiredHash);

		Assert.assertNull(redisCore.getHashField(expiredKey, bytes("name")));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证HDEL核心操作能够批量删除字段，并正确处理空Hash、过期和类型错误。
	 */
	@Test
	public void testDeleteHashFields() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("user:1");

		Assert.assertEquals(0L, redisCore.deleteHashFields(key, List.of(bytes("name"))));

		redisCore.putHashFields(key, Map.of(bytes("name"), bytes("老板"), bytes("city"), bytes("杭州")));
		Assert.assertEquals(1L, redisCore.deleteHashFields(key, List.of(bytes("city"), bytes("missing"), bytes("city"))));
		Assert.assertNotNull(redisCore.get(key));
		Assert.assertEquals(1L, redisCore.deleteHashFields(key, List.of(bytes("name"))));
		Assert.assertNull(redisCore.get(key));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.deleteHashFields(stringKey, List.of(bytes("field")));
			Assert.fail("对String执行HDEL时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-hash");
		RedisHash expiredHash = new RedisHash();
		expiredHash.set(Map.of(bytes("name"), bytes("old")));
		expiredHash.setTimeout(1000L);
		redisCore.put(expiredKey, expiredHash);

		Assert.assertEquals(0L, redisCore.deleteHashFields(expiredKey, List.of(bytes("name"))));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证HLEN核心操作能够读取字段数量，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testGetHashSize() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("user:1");

		Assert.assertEquals(0L, redisCore.getHashSize(key));

		redisCore.putHashFields(key, Map.of(bytes("name"), bytes("老板"), bytes("city"), bytes("杭州")));
		Assert.assertEquals(2L, redisCore.getHashSize(key));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.getHashSize(stringKey);
			Assert.fail("对String执行HLEN时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-hash");
		RedisHash expiredHash = new RedisHash();
		expiredHash.set(Map.of(bytes("name"), bytes("old")));
		expiredHash.setTimeout(1000L);
		redisCore.put(expiredKey, expiredHash);

		Assert.assertEquals(0L, redisCore.getHashSize(expiredKey));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证SADD核心操作能够原子创建Set、过滤重复成员、检查类型并替换过期数据。
	 */
	@Test
	public void testAddSetMembers() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("tags");

		Assert.assertEquals(3L, redisCore.addSetMembers(key, List.of(bytes("java"), bytes("redis"), bytes("中文"), bytes("java"))));
		Assert.assertEquals(1L, redisCore.addSetMembers(key, List.of(bytes("redis"), bytes("netty"))));

		RedisSet redisSet = (RedisSet) redisCore.get(key);
		Assert.assertTrue(redisSet.contains(bytes("java")));
		Assert.assertTrue(redisSet.contains(bytes("中文")));
		Assert.assertTrue(redisSet.contains(bytes("netty")));
		Assert.assertEquals(4L, redisSet.size());

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.addSetMembers(stringKey, List.of(bytes("member")));
			Assert.fail("对String执行SADD时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-set");
		RedisSet expiredSet = new RedisSet();
		expiredSet.add(List.of(bytes("old")));
		expiredSet.setTimeout(1000L);
		redisCore.put(expiredKey, expiredSet);

		Assert.assertEquals(1L, redisCore.addSetMembers(expiredKey, List.of(bytes("new"))));
		RedisSet newSet = (RedisSet) redisCore.get(expiredKey);
		Assert.assertNotSame(expiredSet, newSet);
		Assert.assertEquals(-1L, newSet.timeout());
		Assert.assertFalse(newSet.contains(bytes("old")));
		Assert.assertTrue(newSet.contains(bytes("new")));
	}

	/**
	 * 验证SREM核心操作能够批量删除成员，并正确处理空Set、过期和类型错误。
	 */
	@Test
	public void testRemoveSetMembers() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("tags");

		Assert.assertEquals(0L, redisCore.removeSetMembers(key, List.of(bytes("java"))));

		redisCore.addSetMembers(key, List.of(bytes("java"), bytes("redis"), bytes("中文")));
		Assert.assertEquals(1L, redisCore.removeSetMembers(key, List.of(bytes("redis"), bytes("missing"), bytes("redis"))));
		Assert.assertNotNull(redisCore.get(key));
		Assert.assertEquals(2L, redisCore.removeSetMembers(key, List.of(bytes("java"), bytes("中文"))));
		Assert.assertNull(redisCore.get(key));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.removeSetMembers(stringKey, List.of(bytes("member")));
			Assert.fail("对String执行SREM时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-set");
		RedisSet expiredSet = new RedisSet();
		expiredSet.add(List.of(bytes("old")));
		expiredSet.setTimeout(1000L);
		redisCore.put(expiredKey, expiredSet);

		Assert.assertEquals(0L, redisCore.removeSetMembers(expiredKey, List.of(bytes("old"))));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证SISMEMBER核心操作能够判断成员存在性，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testContainsSetMember() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("tags");

		Assert.assertFalse(redisCore.containsSetMember(key, bytes("java")));

		redisCore.addSetMembers(key, List.of(bytes("java"), bytes("中文")));
		Assert.assertTrue(redisCore.containsSetMember(key, bytes("java")));
		Assert.assertTrue(redisCore.containsSetMember(key, bytes("中文")));
		Assert.assertFalse(redisCore.containsSetMember(key, bytes("missing")));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.containsSetMember(stringKey, bytes("member"));
			Assert.fail("对String执行SISMEMBER时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-set");
		RedisSet expiredSet = new RedisSet();
		expiredSet.add(List.of(bytes("old")));
		expiredSet.setTimeout(1000L);
		redisCore.put(expiredKey, expiredSet);

		Assert.assertFalse(redisCore.containsSetMember(expiredKey, bytes("old")));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证SCARD核心操作能够读取成员数量，并正确处理不存在、过期和类型错误。
	 */
	@Test
	public void testGetSetSize() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = bytes("tags");

		Assert.assertEquals(0L, redisCore.getSetSize(key));

		redisCore.addSetMembers(key, List.of(bytes("java"), bytes("redis"), bytes("中文"), bytes("java")));
		Assert.assertEquals(3L, redisCore.getSetSize(key));

		BytesWrapper stringKey = bytes("string-key");
		redisCore.put(stringKey, new RedisString(bytes("value")));
		try {
			redisCore.getSetSize(stringKey);
			Assert.fail("对String执行SCARD时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			Assert.assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", e.getMessage());
		}

		BytesWrapper expiredKey = bytes("expired-set");
		RedisSet expiredSet = new RedisSet();
		expiredSet.add(List.of(bytes("old")));
		expiredSet.setTimeout(1000L);
		redisCore.put(expiredKey, expiredSet);

		Assert.assertEquals(0L, redisCore.getSetSize(expiredKey));
		Assert.assertNull(redisCore.get(expiredKey));
	}

	/**
	 * 验证SCAN核心操作返回有效key的弱一致性快照，并清理已经过期的key。
	 */
	@Test
	public void testScanKeys() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("java")));

		RedisString expiredValue = new RedisString(bytes("old"));
		expiredValue.setTimeout(1000L);
		redisCore.put(bytes("expired"), expiredValue);

		List<BytesWrapper> keys = redisCore.scanKeys();

		Assert.assertEquals(2, keys.size());
		Assert.assertTrue(keys.contains(bytes("name")));
		Assert.assertTrue(keys.contains(bytes("tags")));
		Assert.assertFalse(keys.contains(bytes("expired")));
		Assert.assertNull(redisCore.get(bytes("expired")));
	}

	/**
	 * 验证DEL只计数存在的key，已过期的数据等同于不存在并被顺手清理。
	 */
	@Test
	public void testDelete() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		redisCore.put(bytes("name"), new RedisString(bytes("twopair")));

		RedisString expiredValue = new RedisString(bytes("old"));
		expiredValue.setTimeout(1000L);
		redisCore.put(bytes("expired"), expiredValue);

		// name存在、missing不存在、expired已过期，只计数name。
		Assert.assertEquals(1L, redisCore.delete(List.of(bytes("name"), bytes("missing"), bytes("expired"))));
		Assert.assertNull(redisCore.get(bytes("name")));

		// 过期数据虽不计数，但已被顺手清理。
		Assert.assertNull(redisCore.get(bytes("expired")));

		// 全部删除后重复删除返回0。
		Assert.assertEquals(0L, redisCore.delete(List.of(bytes("name"))));

		// 空key列表属于参数错误，与其他批量操作的防御保持一致。
		try {
			redisCore.delete(List.of());
			Assert.fail("空key列表应抛出IllegalArgumentException");
		} catch (IllegalArgumentException ignored) {
			// 预期异常。
		}
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
