package cn.twopair;

import cn.twopair.persistence.aof.AofFsyncPolicy;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author ljj
 * @description 验证MiniRedis启动入口对AOF刷盘策略配置的读取行为。
 * @date 2026/8/20
 * @twopair
 */
public class MainTest {
	private String originalAofFsyncValue;

	/**
	 * 保存测试前的系统属性，避免测试污染本地运行环境。
	 */
	@Before
	public void saveSystemProperty() {
		originalAofFsyncValue = System.getProperty(Main.AOF_FSYNC_PROPERTY);
	}

	/**
	 * 恢复测试前的系统属性。
	 */
	@After
	public void restoreSystemProperty() {
		if (originalAofFsyncValue == null) {
			System.clearProperty(Main.AOF_FSYNC_PROPERTY);
		} else {
			System.setProperty(Main.AOF_FSYNC_PROPERTY, originalAofFsyncValue);
		}
	}

	/**
	 * 验证未配置刷盘策略时继续使用ALWAYS。
	 */
	@Test
	public void testLoadDefaultAofFsyncPolicy() {
		System.clearProperty(Main.AOF_FSYNC_PROPERTY);

		Assert.assertEquals(AofFsyncPolicy.ALWAYS, Main.loadAofFsyncPolicy());
	}

	/**
	 * 验证系统属性可以启用EVERYSEC，并且配置值不区分大小写。
	 */
	@Test
	public void testLoadConfiguredAofFsyncPolicy() {
		System.setProperty(Main.AOF_FSYNC_PROPERTY, "everysec");

		Assert.assertEquals(AofFsyncPolicy.EVERYSEC, Main.loadAofFsyncPolicy());
	}

	/**
	 * 验证非法系统属性会在服务启动前明确报错。
	 */
	@Test
	public void testRejectUnsupportedAofFsyncPolicy() {
		System.setProperty(Main.AOF_FSYNC_PROPERTY, "sometimes");

		try {
			Main.loadAofFsyncPolicy();
			Assert.fail("非法AOF刷盘策略应该抛出异常");
		} catch (IllegalArgumentException exception) {
			Assert.assertEquals("不支持的AOF刷盘策略: sometimes", exception.getMessage());
		}
	}
}
