package cn.twopair.command;

import cn.twopair.command.impl.Expire;
import cn.twopair.command.impl.Ping;
import cn.twopair.command.impl.PExpireAt;
import cn.twopair.command.impl.Ttl;
import cn.twopair.command.impl.string.Get;
import cn.twopair.command.impl.string.Set;
import cn.twopair.command.impl.string.SetEx;
import org.junit.Assert;
import org.junit.Test;

/**
 * 测试Redis命令的读写类型划分。
 */
public class WriteCommandTest {

	/**
	 * 验证修改数据的命令实现WriteCommand，而只读命令不实现该接口。
	 */
	@Test
	public void testWriteCommandClassification() {
		Assert.assertTrue(new Set() instanceof WriteCommand);
		Assert.assertTrue(new SetEx() instanceof WriteCommand);
		Assert.assertTrue(new Expire() instanceof WriteCommand);
		Assert.assertTrue(new PExpireAt() instanceof WriteCommand);

		Assert.assertFalse(new Ping() instanceof WriteCommand);
		Assert.assertFalse(new Get() instanceof WriteCommand);
		Assert.assertFalse(new Ttl() instanceof WriteCommand);
	}
}
