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
 * @description 测试SISMEMBER命令的参数解析和成员存在性响应。
 * @date 2026/8/19
 * @twopair
 */
public class SIsMemberTest {

	/**
	 * 验证SISMEMBER对存在和不存在的成员分别返回1和0。
	 */
	@Test
	public void testHandle() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.addSetMembers(bytes("tags"), List.of(bytes("java"), bytes("中文")));
		SIsMember existingCommand = command("tags", "中文");
		SIsMember missingMemberCommand = command("tags", "missing");
		SIsMember missingKeyCommand = command("missing", "java");

		RespInt existingResponse = (RespInt) existingCommand.handle(redisCore);
		RespInt missingMemberResponse = (RespInt) missingMemberCommand.handle(redisCore);
		RespInt missingKeyResponse = (RespInt) missingKeyCommand.handle(redisCore);

		Assert.assertEquals(CommandType.SISMEMBER, existingCommand.type());
		Assert.assertEquals(1L, existingResponse.getValue());
		Assert.assertEquals(0L, missingMemberResponse.getValue());
		Assert.assertEquals(0L, missingKeyResponse.getValue());
	}

	/**
	 * 验证SISMEMBER拒绝参数数量错误、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("SISMEMBER"), bulk("tags")}, "SISMEMBER命令需要key和member两个参数");
		assertInvalid(new Resp[]{bulk("SISMEMBER"), bulk("tags"), bulk("java"), bulk("extra")}, "SISMEMBER命令需要key和member两个参数");
		assertInvalid(new Resp[]{bulk("SISMEMBER"), new RespInt(1L), bulk("java")}, "SISMEMBER的key和member必须是BulkString");
		assertInvalid(new Resp[]{bulk("SISMEMBER"), BulkString.NIL, bulk("java")}, "SISMEMBER的key和member不能是NIL");
		assertInvalid(new Resp[]{bulk("SISMEMBER"), bulk("tags"), BulkString.NIL}, "SISMEMBER的key和member不能是NIL");
	}

	private SIsMember command(String key, String member) {
		SIsMember command = new SIsMember();
		command.setContent(new Resp[]{bulk("SISMEMBER"), bulk(key), bulk(member)});
		return command;
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			SIsMember command = new SIsMember();
			command.setContent(array);
			Assert.fail("非法SISMEMBER参数应抛出IllegalArgumentException");
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
