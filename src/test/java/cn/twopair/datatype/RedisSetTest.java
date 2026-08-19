package cn.twopair.datatype;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author ljj
 * @description 测试Redis集合对象的成员添加、删除、判断和过期时间行为。
 * @date 2026/8/19
 * @twopair
 */
public class RedisSetTest {

	/**
	 * 验证Set只保存唯一成员，并且只统计本次新增的成员数量。
	 */
	@Test
	public void testAddAndContains() {
		RedisSet redisSet = new RedisSet();

		Assert.assertEquals(3L, redisSet.add(List.of(bytes("one"), bytes("two"), bytes("三"), bytes("one"))));
		Assert.assertEquals(1L, redisSet.add(List.of(bytes("two"), bytes("four"))));
		Assert.assertTrue(redisSet.contains(bytes("one")));
		Assert.assertTrue(redisSet.contains(bytes("三")));
		Assert.assertFalse(redisSet.contains(bytes("missing")));
		Assert.assertEquals(4L, redisSet.size());
	}

	/**
	 * 验证删除只统计实际存在的成员，并保留RedisData的TTL能力。
	 */
	@Test
	public void testRemoveAndTimeout() {
		RedisSet redisSet = new RedisSet();
		redisSet.add(List.of(bytes("one"), bytes("two"), bytes("三")));

		Assert.assertEquals(1L, redisSet.remove(List.of(bytes("two"), bytes("missing"), bytes("two"))));
		Assert.assertFalse(redisSet.contains(bytes("two")));
		Assert.assertEquals(2L, redisSet.size());

		Assert.assertEquals(-1L, redisSet.timeout());
		redisSet.setTimeout(13000L);
		Assert.assertEquals(13000L, redisSet.timeout());
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
