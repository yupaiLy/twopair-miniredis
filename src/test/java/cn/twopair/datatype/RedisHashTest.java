package cn.twopair.datatype;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ljj
 * @description 测试Redis哈希对象的字段写入、读取、删除和过期时间行为。
 * @date 2026/8/18
 * @twopair
 */
public class RedisHashTest {

	/**
	 * 验证Hash能够批量写入字段，并且只统计本次新增的字段数量。
	 */
	@Test
	public void testSetAndGet() {
		RedisHash redisHash = new RedisHash();
		Map<BytesWrapper, BytesWrapper> fields = new LinkedHashMap<>();
		fields.put(bytes("name"), bytes("twopair"));
		fields.put(bytes("city"), bytes("杭州"));

		Assert.assertEquals(2L, redisHash.set(fields));
		Assert.assertEquals("twopair", redisHash.get(bytes("name")).toUtf8String());
		Assert.assertEquals("杭州", redisHash.get(bytes("city")).toUtf8String());
		Assert.assertNull(redisHash.get(bytes("missing")));
		Assert.assertEquals(2L, redisHash.size());

		// 覆盖已有字段不算新增字段，但应该保存新值。
		Assert.assertEquals(0L, redisHash.set(Map.of(bytes("name"), bytes("老板"))));
		Assert.assertEquals("老板", redisHash.get(bytes("name")).toUtf8String());
		Assert.assertEquals(2L, redisHash.size());
	}

	/**
	 * 验证删除只统计实际存在的字段，并保留RedisData的TTL能力。
	 */
	@Test
	public void testDeleteAndTimeout() {
		RedisHash redisHash = new RedisHash();
		redisHash.set(Map.of(bytes("name"), bytes("twopair"), bytes("city"), bytes("杭州")));

		Assert.assertEquals(1L, redisHash.delete(List.of(bytes("name"), bytes("missing"))));
		Assert.assertNull(redisHash.get(bytes("name")));
		Assert.assertEquals(1L, redisHash.size());

		Assert.assertEquals(-1L, redisHash.timeout());
		redisHash.setTimeout(12000L);
		Assert.assertEquals(12000L, redisHash.timeout());
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
