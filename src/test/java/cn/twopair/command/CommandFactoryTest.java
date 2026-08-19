package cn.twopair.command;

import cn.twopair.command.impl.Expire;
import cn.twopair.command.impl.Ping;
import cn.twopair.command.impl.Ttl;
import cn.twopair.command.impl.list.LLen;
import cn.twopair.command.impl.list.LPop;
import cn.twopair.command.impl.list.LPush;
import cn.twopair.command.impl.list.LRange;
import cn.twopair.command.impl.string.Get;
import cn.twopair.command.impl.string.Set;
import cn.twopair.command.impl.string.SetEx;
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

		RespArray expireArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper(
						"EXPIRE".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(new BytesWrapper(
						"name".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(new BytesWrapper(
						"10".getBytes(StandardCharsets.UTF_8)
				))
		});

		Command expire = CommandFactory.from(expireArray);
		Assert.assertTrue(expire instanceof Expire);
		RespArray ttlArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper(
						"TTL".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(new BytesWrapper(
						"name".getBytes(StandardCharsets.UTF_8)
				))
		});
		Command ttl = CommandFactory.from(ttlArray);
		Assert.assertTrue(ttl instanceof Ttl);
	}

	/**
	 * 验证命令工厂能够根据SETEX请求创建对应的命令对象。
	 */
	@Test
	public void testCreateSetEx() {
		RespArray setExArray = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("setex".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("10".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(setExArray);

		Assert.assertTrue(command instanceof SetEx);
		Assert.assertEquals(CommandType.SETEX, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写LPUSH请求创建对应命令。
	 */
	@Test
	public void testCreateLPush() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("lpush".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("one".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof LPush);
		Assert.assertEquals(CommandType.LPUSH, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写LPOP请求创建对应命令。
	 */
	@Test
	public void testCreateLPop() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("lpop".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof LPop);
		Assert.assertEquals(CommandType.LPOP, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写LLEN请求创建对应命令。
	 */
	@Test
	public void testCreateLLen() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("llen".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof LLen);
		Assert.assertEquals(CommandType.LLEN, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写LRANGE请求创建对应命令。
	 */
	@Test
	public void testCreateLRange() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("lrange".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("0".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("-1".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof LRange);
		Assert.assertEquals(CommandType.LRANGE, command.type());
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
