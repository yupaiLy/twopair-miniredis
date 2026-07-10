package cn.twopair.command;

import cn.twopair.command.impl.Ping;
import cn.twopair.command.impl.string.Get;
import cn.twopair.command.impl.string.Set;
import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.resp.SimpleString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description 命令工厂测试
 * @date 2026/7/10
 * @twopair
 */
public class CommandFactoryTest {

	@Test
	public void testFrom() {
		RespArray pingArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("PING".getBytes(StandardCharsets.UTF_8)))
		});
		Command ping = CommandFactory.from(pingArray);
		Assert.assertTrue(ping instanceof Ping);

		RespArray setArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("SET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		});
		Command set = CommandFactory.from(setArray);
		Assert.assertTrue(set instanceof Set);

		RespArray getArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("GET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))
		});
		Command get = CommandFactory.from(getArray);
		Assert.assertTrue(get instanceof Get);
	}

	@Test
	public void testUnsupportedCommand() {
		RespArray unknownArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("UNKNOWN".getBytes(StandardCharsets.UTF_8)))
		});

		try {
			CommandFactory.from(unknownArray);
			Assert.fail("预期应该抛出 IllegalArgumentException，但没有抛出");
		} catch (IllegalArgumentException e) {
			Assert.assertNotNull(e);
		}
	}
	@Test
	public void testCommandHandleFlow() {
		RedisCore redisCore = new RedisCoreImpl();

		RespArray setArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("SET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		});
		Command set = CommandFactory.from(setArray);
		Resp setResp = set.handle(redisCore);
		Assert.assertTrue(setResp instanceof SimpleString);
		Assert.assertEquals("OK", ((SimpleString) setResp).getContent());

		RespArray getArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("GET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))
		});
		Command get = CommandFactory.from(getArray);
		Resp getResp = get.handle(redisCore);
		Assert.assertTrue(getResp instanceof BulkString);
		Assert.assertEquals("twopair", ((BulkString) getResp).getBytesWrapper().toUtf8String());
	}
}