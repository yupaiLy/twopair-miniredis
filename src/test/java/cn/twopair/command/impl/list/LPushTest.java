package cn.twopair.command.impl.list;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisList;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试LPUSH命令的参数解析、列表写入和返回值。
 * @date 2026/8/18
 * @twopair
 */
public class LPushTest {

	/**
	 * 验证LPUSH能够按参数顺序压入元素并返回列表长度。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		LPush command = new LPush();
		command.setContent(new Resp[]{bulk("LPUSH"), bulk("letters"), bulk("one"), bulk("two"), bulk("三")});

		RespInt response = (RespInt) command.handle(redisCore);

		Assert.assertEquals(CommandType.LPUSH, command.type());
		Assert.assertEquals(3L, response.getValue());
		RedisList redisList = (RedisList) redisCore.get(bytes("letters"));
		Assert.assertEquals("三", redisList.leftPop().toUtf8String());
		Assert.assertEquals("two", redisList.leftPop().toUtf8String());
		Assert.assertEquals("one", redisList.leftPop().toUtf8String());
	}

	/**
	 * 验证LPUSH会拒绝参数不足和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("LPUSH"), bulk("letters")}, "LPUSH命令需要key和至少一个element参数");
		assertInvalid(new Resp[]{bulk("LPUSH"), BulkString.NIL, bulk("one")}, "LPUSH的key不能是NIL");
		assertInvalid(new Resp[]{bulk("LPUSH"), bulk("letters"), BulkString.NIL}, "LPUSH的element不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			LPush command = new LPush();
			command.setContent(array);
			Assert.fail("非法LPUSH参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(message, e.getMessage());
		}
	}

	private BulkString bulk(String value) {
		return new BulkString(bytes(value));
	}

	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
