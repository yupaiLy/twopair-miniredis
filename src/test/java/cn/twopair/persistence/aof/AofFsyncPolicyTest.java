package cn.twopair.persistence.aof;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author ljj
 * @description 测试AOF刷盘策略的配置解析行为。
 * @date 2026/8/20
 * @twopair
 */
public class AofFsyncPolicyTest {

	/**
	 * 验证支持的策略名称可以忽略大小写和首尾空白。
	 */
	@Test
	public void testParseSupportedPolicies() {
		Assert.assertEquals(
				AofFsyncPolicy.ALWAYS,
				AofFsyncPolicy.parse("always")
		);
		Assert.assertEquals(
				AofFsyncPolicy.ALWAYS,
				AofFsyncPolicy.parse("ALWAYS")
		);
		Assert.assertEquals(
				AofFsyncPolicy.EVERYSEC,
				AofFsyncPolicy.parse("everysec")
		);
		Assert.assertEquals(
				AofFsyncPolicy.EVERYSEC,
				AofFsyncPolicy.parse("  EVERYSEC  ")
		);
	}

	/**
	 * 验证空值、空字符串和未支持的策略会被明确拒绝。
	 */
	@Test
	public void testRejectInvalidPolicies() {
		assertInvalidPolicy(null);
		assertInvalidPolicy("");
		assertInvalidPolicy("   ");
		assertInvalidPolicy("no");
		assertInvalidPolicy("unknown");
	}

	/**
	 * 断言指定配置值无法解析为AOF刷盘策略。
	 *
	 * @param value 待解析的配置值
	 */
	private void assertInvalidPolicy(String value) {
		try {
			AofFsyncPolicy.parse(value);
			Assert.fail("应当拒绝非法AOF刷盘策略: " + value);
		} catch (IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
			Assert.assertFalse(expected.getMessage().isBlank());
		}
	}
}
