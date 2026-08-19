package cn.twopair.command.impl.string;

import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
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
 * @description GET命令测试
 * @date 2026/7/10
 * @twopair
 */
public class GetTest {

	@Test
	public void testGet() {
		RedisCore redisCore = new RedisCoreImpl();

		Set set = new Set();
		set.setContent(new Resp[]{
				new BulkString(new BytesWrapper("SET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		});
		set.handle(redisCore);

		Get get = new Get();
		get.setContent(new Resp[]{
				new BulkString(new BytesWrapper("GET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))
		});

		Resp resp = get.handle(redisCore);

		Assert.assertTrue(resp instanceof BulkString);
		Assert.assertEquals("twopair", ((BulkString) resp).getBytesWrapper().toUtf8String());
	}

	@Test
	public void testGetNil() {
		RedisCore redisCore = new RedisCoreImpl();

		Get get = new Get();
		get.setContent(new Resp[]{
				new BulkString(new BytesWrapper("GET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("not_exists".getBytes(StandardCharsets.UTF_8)))
		});

		Resp resp = get.handle(redisCore);

		Assert.assertSame(BulkString.NIL, resp);
	}

	/**
	 * 验证GET对非String类型抛出WrongTypeException，返回标准WRONGTYPE错误。
	 */
	@Test
	public void testGetWrongType() {
		RedisCore redisCore = new RedisCoreImpl();
		redisCore.leftPush(bytes("tags"), List.of(bytes("java")));

		Get get = new Get();
		get.setContent(new Resp[]{bulk("GET"), bulk("tags")});

		try {
			get.handle(redisCore);
			Assert.fail("对List执行GET时应抛出WrongTypeException");
		} catch (WrongTypeException e) {
			// 预期的WRONGTYPE错误。
		}
	}

	/**
	 * 验证GET拒绝参数数量错误、错误RESP类型和NIL参数。
	 */
	@Test
	public void testRejectInvalidArguments() {
		assertInvalid(new Resp[]{bulk("GET")}, "GET命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("GET"), bulk("name"), bulk("extra")}, "GET命令需要key一个参数");
		assertInvalid(new Resp[]{bulk("GET"), new RespInt(1L)}, "GET的key必须是BulkString");
		assertInvalid(new Resp[]{bulk("GET"), BulkString.NIL}, "GET的key不能是NIL");
	}

	private void assertInvalid(Resp[] array, String message) {
		try {
			Get get = new Get();
			get.setContent(array);
			Assert.fail("非法GET参数应抛出IllegalArgumentException");
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