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
 * @author ljj
 * @description 测试LLEN命令的参数解析和列表长度响应。
 * @date 2026/8/18
 * @twopair
 */
public class LLenTest {

	/**
	 * 验证LLEN能够返回列表长度，不存在的key返回0。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.leftPush(bytes("letters"), List.of(bytes("one"), bytes("two"), bytes("三")));
		LLen existingCommand = new LLen();
		existingCommand.setContent(new Resp[]{bulk("LLEN"), bulk("letters")});
		LLen missingCommand = new LLen();
		missingCommand.setContent(new Resp[]{bulk("LLEN"), bulk("missing")});

		RespInt existingResponse = (RespInt) existingCommand.handle(redisCore);
		RespInt missingResponse = (RespInt) missingCommand.handle(redisCore);

		Assert.assertEquals(CommandType.LLEN, existingCommand.type());
		Assert.assertEquals(3L, existingResponse.getValue());
		Assert.assertEquals(0L, missingResponse.getValue());
	}

	/**
	 * 验证LLEN会拒绝参数数量错误、非BulkString类型和NIL key。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("LLEN")}, "LLEN命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("LLEN"), bulk("letters"), bulk("extra")}, "LLEN命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("LLEN"), new RespInt(1L)}, "LLEN的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("LLEN"), BulkString.NIL}, "LLEN的key不能是NIL");
	}

	/**
	 * 断言指定参数会触发预期异常。
	 *
	 * @param array 命令参数
	 * @param message 预期错误信息
	 */
	private void assertInvalid(Resp[] array, String message) {
		try {
			LLen command = new LLen();
			command.setContent(array);
			Assert.fail("非法LLEN参数应抛出IllegalArgumentException");
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
