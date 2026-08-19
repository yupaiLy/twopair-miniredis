package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import cn.twopair.resp.SimpleString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试SELECT 0兼容命令的参数解析和响应。
 * @date 2026/8/19
 * @twopair
 */
public class SelectTest {

	/**
	 * 验证SELECT 0返回OK，兼容客户端连接时的默认数据库选择。
	 */
	@Test
	public void testHandleDatabaseZero() {
		RedisCore redisCore = new RedisCoreImpl();
		Select command = new Select();
		command.setContent(new Resp[]{bulk("SELECT"), bulk("0")});

		SimpleString response = (SimpleString) command.handle(redisCore);

		Assert.assertEquals(CommandType.SELECT, command.type());
		Assert.assertEquals("OK", response.getContent());
	}

	/**
	 * 验证SELECT拒绝错误参数以及当前尚未支持的非零数据库。
	 */
	@Test
	public void testRejectInvalidOrUnsupportedDatabase() {
		assertInvalid(new Resp[]{bulk("SELECT")}, "SELECT命令需要database一个参数");
		assertInvalid(new Resp[]{bulk("SELECT"), bulk("0"), bulk("extra")}, "SELECT命令需要database一个参数");
		assertInvalid(new Resp[]{bulk("SELECT"), new RespInt(0L)}, "SELECT的database必须是BulkString");
		assertInvalid(new Resp[]{bulk("SELECT"), BulkString.NIL}, "SELECT的database必须是整数");
		assertInvalid(new Resp[]{bulk("SELECT"), bulk("database")}, "SELECT的database必须是整数");
		assertInvalid(new Resp[]{bulk("SELECT"), bulk("1")}, "当前仅支持database 0");
		assertInvalid(new Resp[]{bulk("SELECT"), bulk("-1")}, "当前仅支持database 0");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			Select command = new Select();
			command.setContent(array);
			Assert.fail("非法SELECT参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(message, e.getMessage());
		}
	}

	private BulkString bulk(String value) {
		return new BulkString(new BytesWrapper(value.getBytes(StandardCharsets.UTF_8)));
	}
}
