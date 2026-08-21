package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Errors;
import cn.twopair.resp.Resp;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 测试BGREWRITEAOF命令的参数校验和基础响应。
 *
 * @author ljj
 */
public class BgRewriteAofTest {

	/**
	 * 验证BGREWRITEAOF的命令类型和无参数语义。
	 */
	@Test
	public void testCommandTypeAndArguments() {
		BgRewriteAof command = new BgRewriteAof();

		command.setContent(new Resp[]{bulkString("BGREWRITEAOF")});

		Assert.assertEquals(CommandType.BGREWRITEAOF, command.type());
	}

	/**
	 * 验证BGREWRITEAOF拒绝额外参数。
	 */
	@Test
	public void testRejectExtraArgument() {
		BgRewriteAof command = new BgRewriteAof();

		IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class, () -> command.setContent(new Resp[]{bulkString("BGREWRITEAOF"), bulkString("extra")}));

		Assert.assertEquals("BGREWRITEAOF命令不需要参数", exception.getMessage());
	}

	/**
	 * 验证服务未启用AOF时返回明确错误。
	 */
	@Test
	public void testRejectWhenAofIsDisabled() {
		BgRewriteAof command = new BgRewriteAof();
		command.setContent(new Resp[]{bulkString("BGREWRITEAOF")});

		Resp response = command.handle(new RedisCoreImpl());

		Assert.assertTrue(response instanceof Errors);
		Assert.assertEquals("ERR AOF未启用", ((Errors) response).getContent());
	}

	/**
	 * 构造RESP Bulk String。
	 *
	 * @param value 字符串内容
	 * @return RESP Bulk String
	 */
	private BulkString bulkString(String value) {
		return new BulkString(new BytesWrapper(value.getBytes(StandardCharsets.UTF_8)));
	}
}
