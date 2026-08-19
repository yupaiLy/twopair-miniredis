package cn.twopair.command.impl.hash;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisHash;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 测试HSET命令的参数解析、批量字段写入和新增字段计数。
 * @date 2026/8/19
 * @twopair
 */
public class HSetTest {

	/**
	 * 验证HSET支持多组field和value，并且覆盖字段不计入返回值。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		HSet firstCommand = new HSet();
		firstCommand.setContent(new Resp[]{bulk("HSET"), bulk("user:1"), bulk("name"), bulk("twopair"), bulk("city"), bulk("杭州")});
		HSet secondCommand = new HSet();
		secondCommand.setContent(new Resp[]{bulk("HSET"), bulk("user:1"), bulk("name"), bulk("老板"), bulk("age"), bulk("18")});

		RespInt firstResponse = (RespInt) firstCommand.handle(redisCore);
		RespInt secondResponse = (RespInt) secondCommand.handle(redisCore);

		Assert.assertEquals(CommandType.HSET, firstCommand.type());
		Assert.assertEquals(2L, firstResponse.getValue());
		Assert.assertEquals(1L, secondResponse.getValue());
		RedisHash redisHash = (RedisHash) redisCore.get(bytes("user:1"));
		Assert.assertEquals("老板", redisHash.get(bytes("name")).toUtf8String());
		Assert.assertEquals("杭州", redisHash.get(bytes("city")).toUtf8String());
		Assert.assertEquals("18", redisHash.get(bytes("age")).toUtf8String());
	}

	/**
	 * 验证HSET拒绝不成对参数、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("HSET"), bulk("user:1"), bulk("name")}, "HSET命令需要key和至少一组field、value参数");
		assertInvalid(new Resp[]{bulk("HSET"), bulk("user:1"), bulk("name"), bulk("value"), bulk("extra")}, "HSET命令需要key和至少一组field、value参数");
		assertInvalid(new Resp[]{bulk("HSET"), new RespInt(1L), bulk("name"), bulk("value")}, "HSET的key、field和value必须是BulkString");
		assertInvalid(new Resp[]{bulk("HSET"), BulkString.NIL, bulk("name"), bulk("value")}, "HSET的key不能是NIL");
		assertInvalid(new Resp[]{bulk("HSET"), bulk("user:1"), BulkString.NIL, bulk("value")}, "HSET的field和value不能是NIL");
		assertInvalid(new Resp[]{bulk("HSET"), bulk("user:1"), bulk("name"), BulkString.NIL}, "HSET的field和value不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			HSet command = new HSet();
			command.setContent(array);
			Assert.fail("非法HSET参数应抛出IllegalArgumentException");
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
