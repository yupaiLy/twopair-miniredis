package cn.twopair.command.impl.hash;

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
 * 测试HMSET命令的参数解析、批量字段写入和兼容响应。
 *
 * @author ljj
 */
public class HMSetTest {

	/**
	 * 验证HMSET能够批量写入和覆盖Hash字段，并按照Redis协议返回OK。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		HMSet firstCommand = new HMSet();
		firstCommand.setContent(new Resp[]{bulk("HMSET"), bulk("user:1"), bulk("name"), bulk("twopair"), bulk("city"), bulk("杭州")});
		HMSet secondCommand = new HMSet();
		secondCommand.setContent(new Resp[]{bulk("HMSET"), bulk("user:1"), bulk("name"), bulk("老板")});

		SimpleString firstResponse = (SimpleString) firstCommand.handle(redisCore);
		SimpleString secondResponse = (SimpleString) secondCommand.handle(redisCore);

		Assert.assertEquals(CommandType.HMSET, firstCommand.type());
		Assert.assertEquals("OK", firstResponse.getContent());
		Assert.assertEquals("OK", secondResponse.getContent());
		Assert.assertEquals("老板", redisCore.getHashField(bytes("user:1"), bytes("name")).toUtf8String());
		Assert.assertEquals("杭州", redisCore.getHashField(bytes("user:1"), bytes("city")).toUtf8String());
	}

	/**
	 * 验证HMSET拒绝不成对参数、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HMSET"), bulk("user:1"), bulk("name")}, "HMSET命令需要key和至少一组field、value参数");
		assertInvalid(new Resp[]{bulk("HMSET"), bulk("user:1"), bulk("name"), bulk("value"), bulk("extra")}, "HMSET命令需要key和至少一组field、value参数");
		assertInvalid(new Resp[]{bulk("HMSET"), new RespInt(1L), bulk("name"), bulk("value")}, "HMSET的key、field和value必须是BulkString");
		assertInvalid(new Resp[]{bulk("HMSET"), BulkString.NIL, bulk("name"), bulk("value")}, "HMSET的key不能是NIL");
		assertInvalid(new Resp[]{bulk("HMSET"), bulk("user:1"), BulkString.NIL, bulk("value")}, "HMSET的field和value不能是NIL");
		assertInvalid(new Resp[]{bulk("HMSET"), bulk("user:1"), bulk("name"), BulkString.NIL}, "HMSET的field和value不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			HMSet command = new HMSet();
			command.setContent(array);
			Assert.fail("非法HMSET参数应抛出IllegalArgumentException");
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
