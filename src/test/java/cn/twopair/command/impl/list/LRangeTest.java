package cn.twopair.command.impl.list;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author ljj
 * @description 测试LRANGE命令的参数解析和范围查询响应。
 * @date 2026/8/18
 * @twopair
 */
public class LRangeTest {

	/**
	 * 验证LRANGE能够解析负数下标并返回BulkString数组。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.leftPush(bytes("letters"), List.of(bytes("one"), bytes("two"), bytes("三")));
		LRange command = new LRange();
		command.setContent(new Resp[]{bulk("LRANGE"), bulk("letters"), bulk("-2"), bulk("-1")});

		RespArray response = (RespArray) command.handle(redisCore);

		Assert.assertEquals(CommandType.LRANGE, command.type());
		Assert.assertEquals(2, response.getArray().length);
		Assert.assertEquals("two", ((BulkString) response.getArray()[0]).getBytesWrapper().toUtf8String());
		Assert.assertEquals("one", ((BulkString) response.getArray()[1]).getBytesWrapper().toUtf8String());
	}

	/**
	 * 验证LRANGE会拒绝参数数量、参数类型、NIL和非法整数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("LRANGE"), bulk("letters"), bulk("0")}, "LRANGE命令需要key、start和stop三个参数");
		assertInvalid(new Resp[]{bulk("LRANGE"), new RespInt(1L), bulk("0"), bulk("1")}, "LRANGE的key、start和stop必须是BulkString");
		assertInvalid(new Resp[]{bulk("LRANGE"), BulkString.NIL, bulk("0"), bulk("1")}, "LRANGE的key不能是NIL");
		assertInvalid(new Resp[]{bulk("LRANGE"), bulk("letters"), bulk("start"), bulk("1")}, "LRANGE的start和stop必须是整数");
		assertInvalid(new Resp[]{bulk("LRANGE"), bulk("letters"), bulk("0"), BulkString.NIL}, "LRANGE的start和stop必须是整数");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			LRange command = new LRange();
			command.setContent(array);
			Assert.fail("非法LRANGE参数应抛出IllegalArgumentException");
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
