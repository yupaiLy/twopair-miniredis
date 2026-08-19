package cn.twopair.command.impl.list;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试LPOP命令的参数解析、列表弹出和空结果响应。
 * @date 2026/8/18
 * @twopair
 */
public class LPopTest {

	/**
	 * 验证LPOP能够弹出列表头部元素，并在列表不存在时返回NIL。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.leftPush(bytes("letters"), java.util.List.of(bytes("one"), bytes("two"), bytes("三")));
		LPop command = new LPop();
		command.setContent(new Resp[]{bulk("LPOP"), bulk("letters")});

		BulkString firstResponse = (BulkString) command.handle(redisCore);
		BulkString secondResponse = (BulkString) command.handle(redisCore);
		BulkString thirdResponse = (BulkString) command.handle(redisCore);
		BulkString nilResponse = (BulkString) command.handle(redisCore);

		Assert.assertEquals(CommandType.LPOP, command.type());
		Assert.assertEquals("三", firstResponse.getBytesWrapper().toUtf8String());
		Assert.assertEquals("two", secondResponse.getBytesWrapper().toUtf8String());
		Assert.assertEquals("one", thirdResponse.getBytesWrapper().toUtf8String());
		Assert.assertSame(BulkString.NIL, nilResponse);
	}

	/**
	 * 验证LPOP会拒绝参数数量错误、非BulkString类型和NIL key。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("LPOP")}, "LPOP命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("LPOP"), bulk("letters"), bulk("extra")}, "LPOP命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("LPOP"), new RespInt(1L)}, "LPOP的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("LPOP"), BulkString.NIL}, "LPOP的key不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			LPop command = new LPop();
			command.setContent(array);
			Assert.fail("非法LPOP参数应抛出IllegalArgumentException");
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
