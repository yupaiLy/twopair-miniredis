package cn.twopair.command.impl;

import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author ljj
 * @description
 * @date 2026/7/10
 * @twopair
 */
public class PingTest {
	@Test
	public void testPing() {
		Ping ping = new Ping();

		Resp resp = ping.handle(new RedisCoreImpl());

		Assert.assertTrue(resp instanceof SimpleString);
		Assert.assertEquals("PONG", ((SimpleString) resp).getContent());
	}
}
