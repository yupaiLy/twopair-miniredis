package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author ljj
 * @description
 * @date 2026/4/9
 * @twopair
 */
public class RedisCoreImplTest {
	private final RedisCore core = new RedisCoreImpl();

	@Test
	public void testRedisCore() throws InterruptedException {

		BytesWrapper key = new BytesWrapper("name".getBytes());
		RedisString value = new RedisString();
		value.setValue(new BytesWrapper("twopair".getBytes()));
		value.setTimeout(System.currentTimeMillis() + 500);

		core.put(key, value);

		RedisData data = core.get(key);
		Assert.assertNotNull(data);
		System.out.println(core.exist(key));
		RedisString str = (RedisString) data;
		System.out.println(str.getValue().toUtf8String());
		// 延迟 1秒
		Thread.sleep(1000);
		System.out.println(core.exist(key));
		data = core.get(key);
		Assert.assertNull(data);
	}

}