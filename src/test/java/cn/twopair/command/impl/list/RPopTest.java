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
import java.util.List;

/**
 * 测试RPOP命令的参数解析、列表弹出和空结果响应。
 *
 * @author ljj
 */
public class RPopTest {

	/**
	 * 验证RPOP从列表尾部弹出元素，并在列表不存在时返回NIL。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.rightPush(bytes("letters"), List.of(bytes("one"), bytes("two"), bytes("三")));
		RPop command = new RPop();
		command.setContent(new Resp[]{bulk("RPOP"), bulk("letters")});

		BulkString firstResponse = (BulkString) command.handle(redisCore);
		BulkString secondResponse = (BulkString) command.handle(redisCore);
		BulkString thirdResponse = (BulkString) command.handle(redisCore);
		BulkString nilResponse = (BulkString) command.handle(redisCore);

		Assert.assertEquals(CommandType.RPOP, command.type());
		Assert.assertEquals("三", firstResponse.getBytesWrapper().toUtf8String());
		Assert.assertEquals("two", secondResponse.getBytesWrapper().toUtf8String());
		Assert.assertEquals("one", thirdResponse.getBytesWrapper().toUtf8String());
		Assert.assertSame(BulkString.NIL, nilResponse);
	}

	/**
	 * 验证RPOP拒绝参数数量错误、错误类型和NIL key。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("RPOP")}, "RPOP命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("RPOP"), bulk("letters"), bulk("extra")}, "RPOP命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("RPOP"), new RespInt(1L)}, "RPOP的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("RPOP"), BulkString.NIL}, "RPOP的key不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array   命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			RPop command = new RPop();
			command.setContent(array);
			Assert.fail("非法RPOP参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(message, e.getMessage());
		}
	}

	/**
	 * 创建BulkString参数。
	 *
	 * @param value 文本内容
	 * @return BulkString参数
	 */
	private BulkString bulk(String value) {
		return new BulkString(bytes(value));
	}

	/**
	 * 将文本转换为UTF-8字节包装对象。
	 *
	 * @param value 文本内容
	 * @return UTF-8字节包装对象
	 */
	private BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
