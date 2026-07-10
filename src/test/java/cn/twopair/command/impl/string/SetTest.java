package cn.twopair.command.impl.string;

import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description
 * @date 2026/7/10
 * @twopair
 */
public class SetTest {
	@Test
	public void testSet() {
		RedisCoreImpl redisCore = new RedisCoreImpl();
		Set set = new Set();
		Resp[] resps = new Resp[]{
				new BulkString(new BytesWrapper("SET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		};


		set.setContent(resps);
		Resp resp = set.handle(redisCore);

		Assert.assertTrue(resp instanceof SimpleString);
		Assert.assertEquals("OK", ((SimpleString) resp).getContent());

		RedisData data = redisCore.get(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)));
		Assert.assertTrue(data instanceof RedisString);
		Assert.assertEquals("twopair", ((RedisString) data).getValue().toUtf8String());
	}
}
