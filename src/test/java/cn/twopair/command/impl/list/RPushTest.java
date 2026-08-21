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
 * 测试RPUSH命令的参数解析、列表写入和返回值。
 *
 * @author ljj
 */
public class RPushTest {

	/**
	 * 验证RPUSH按照参数顺序压入列表尾部并返回列表长度。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		RPush command = new RPush();
		command.setContent(new Resp[]{bulk("RPUSH"), bulk("letters"), bulk("one"), bulk("two"), bulk("三")});

		RespInt response = (RespInt) command.handle(redisCore);

		Assert.assertEquals(CommandType.RPUSH, command.type());
		Assert.assertEquals(3L, response.getValue());
		List<BytesWrapper> elements = redisCore.listRange(bytes("letters"), 0L, -1L);
		Assert.assertEquals("one", elements.get(0).toUtf8String());
		Assert.assertEquals("two", elements.get(1).toUtf8String());
		Assert.assertEquals("三", elements.get(2).toUtf8String());
	}

	/**
	 * 验证RPUSH拒绝参数不足、错误类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("RPUSH"), bulk("letters")}, "RPUSH命令需要key和至少一个element参数");
		assertInvalid(new Resp[]{bulk("RPUSH"), new RespInt(1L), bulk("one")}, "RPUSH的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("RPUSH"), BulkString.NIL, bulk("one")}, "RPUSH的key不能是NIL");
		assertInvalid(new Resp[]{bulk("RPUSH"), bulk("letters"), new RespInt(1L)}, "RPUSH的element必须是BulkString");
		assertInvalid(new Resp[]{bulk("RPUSH"), bulk("letters"), BulkString.NIL}, "RPUSH的element不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			RPush command = new RPush();
			command.setContent(array);
			Assert.fail("非法RPUSH参数应抛出IllegalArgumentException");
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
