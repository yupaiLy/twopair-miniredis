package cn.twopair.datatype;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ljj
 * @description 测试Redis列表对象的头部压入、弹出和过期时间行为。
 * @date 2026/8/18
 * @twopair
 */
public class RedisListTest {

	/**
	 * 验证多个元素按照LPUSH语义依次插入头部，并能从头部依次弹出。
	 */
	@Test
	public void testLeftPushAndLeftPop() {
		RedisList redisList = new RedisList();

		long length = redisList.leftPush(List.of(
				bytes("one"),
				bytes("two"),
				bytes("三")
		));

		Assert.assertEquals(3L, length);
		Assert.assertEquals(3L, redisList.size());
		Assert.assertEquals("三", redisList.leftPop().toUtf8String());
		Assert.assertEquals("two", redisList.leftPop().toUtf8String());
		Assert.assertEquals("one", redisList.leftPop().toUtf8String());
		Assert.assertNull(redisList.leftPop());
		Assert.assertEquals(0L, redisList.size());

		Assert.assertEquals(-1L, redisList.timeout());
		redisList.setTimeout(11000L);
		Assert.assertEquals(11000L, redisList.timeout());
	}

	/**
	 * 验证列表范围查询支持闭区间、负数下标和越界裁剪。
	 */
	@Test
	public void testRange() {
		RedisList redisList = new RedisList();
		redisList.leftPush(List.of(bytes("one"), bytes("two"), bytes("三")));

		Assert.assertEquals(List.of("三", "two", "one"), toText(redisList.range(0L, -1L)));
		Assert.assertEquals(List.of("三", "two"), toText(redisList.range(0L, 1L)));
		Assert.assertEquals(List.of("two", "one"), toText(redisList.range(-2L, -1L)));
		Assert.assertEquals(List.of("三", "two", "one"), toText(redisList.range(-100L, 100L)));
		Assert.assertTrue(redisList.range(3L, 4L).isEmpty());
		Assert.assertTrue(redisList.range(2L, 1L).isEmpty());
		Assert.assertTrue(redisList.range(0L, -4L).isEmpty());
	}

	/**
	 * 将字节包装器列表转换成便于断言的UTF-8字符串列表。
	 *
	 * @param values 字节包装器列表
	 * @return UTF-8字符串列表
	 */
	private List<String> toText(List<BytesWrapper> values) {
		List<String> result = new ArrayList<>(values.size());
		for (BytesWrapper value : values) {
			result.add(value.toUtf8String());
		}
		return result;
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
