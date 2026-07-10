package cn.twopair.command.impl.string;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

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
}