package cn.twopair.command;

import cn.twopair.command.impl.Expire;
import cn.twopair.command.impl.Ping;
import cn.twopair.command.impl.Ttl;
import cn.twopair.command.impl.hash.HDel;
import cn.twopair.command.impl.hash.HGet;
import cn.twopair.command.impl.hash.HLen;
import cn.twopair.command.impl.hash.HSet;
import cn.twopair.command.impl.list.LLen;
import cn.twopair.command.impl.list.LPop;
import cn.twopair.command.impl.list.LPush;
import cn.twopair.command.impl.list.LRange;
import cn.twopair.command.impl.set.SAdd;
import cn.twopair.command.impl.set.SCard;
import cn.twopair.command.impl.set.SIsMember;
import cn.twopair.command.impl.set.SRem;
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

	/**
	 * 验证命令工厂能够根据小写HSET请求创建对应命令。
	 */
	@Test
	public void testCreateHSet() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("hset".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof HSet);
		Assert.assertEquals(CommandType.HSET, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写HGET请求创建对应命令。
	 */
	@Test
	public void testCreateHGet() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("hget".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof HGet);
		Assert.assertEquals(CommandType.HGET, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写HDEL请求创建对应命令。
	 */
	@Test
	public void testCreateHDel() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("hdel".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof HDel);
		Assert.assertEquals(CommandType.HDEL, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写HLEN请求创建对应命令。
	 */
	@Test
	public void testCreateHLen() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("hlen".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof HLen);
		Assert.assertEquals(CommandType.HLEN, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写SADD请求创建对应命令。
	 */
	@Test
	public void testCreateSAdd() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("sadd".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("java".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof SAdd);
		Assert.assertEquals(CommandType.SADD, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写SREM请求创建对应命令。
	 */
	@Test
	public void testCreateSRem() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("srem".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("java".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof SRem);
		Assert.assertEquals(CommandType.SREM, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写SISMEMBER请求创建对应命令。
	 */
	@Test
	public void testCreateSIsMember() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("sismember".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("java".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof SIsMember);
		Assert.assertEquals(CommandType.SISMEMBER, command.type());
	}

	/**
	 * 验证命令工厂能够根据小写SCARD请求创建对应命令。
	 */
	@Test
	public void testCreateSCard() {
		RespArray array = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("scard".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8)))
		});

		Command command = CommandFactory.from(array);

		Assert.assertTrue(command instanceof SCard);
		Assert.assertEquals(CommandType.SCARD, command.type());
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
