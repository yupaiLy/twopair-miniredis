package cn.twopair.command.impl.set;

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
 * @author ljj
 * @description 测试SCARD命令的参数解析和Set成员数量响应。
 * @date 2026/8/19
 * @twopair
 */
public class SCardTest {

	/**
	 * 验证SCARD返回Set的唯一成员数量，不存在的key返回0。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("java"), bytes("redis"), bytes("中文"), bytes("java")));
		SCard existingCommand = command("tags");
		SCard missingCommand = command("missing");

		RespInt existingResponse = (RespInt) existingCommand.handle(redisCore);
		RespInt missingResponse = (RespInt) missingCommand.handle(redisCore);

		Assert.assertEquals(CommandType.SCARD, existingCommand.type());
		Assert.assertEquals(3L, existingResponse.getValue());
		Assert.assertEquals(0L, missingResponse.getValue());
	}

	/**
	 * 验证SCARD拒绝参数数量错误、错误RESP类型和NIL key。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("SCARD")}, "SCARD命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("SCARD"), bulk("tags"), bulk("extra")}, "SCARD命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("SCARD"), new RespInt(1L)}, "SCARD的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("SCARD"), BulkString.NIL}, "SCARD的key不能是NIL");
	}

	private SCard command(String key) {
		SCard command = new SCard();
		command.setContent(new Resp[]{bulk("SCARD"), bulk(key)});
		return command;
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			SCard command = new SCard();
			command.setContent(array);
			Assert.fail("非法SCARD参数应抛出IllegalArgumentException");
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
