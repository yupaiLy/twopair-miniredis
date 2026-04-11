package cn.twopair.resp;

import cn.twopair.datatype.BytesWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * @author ljj
 * @description
 * @date 2026/4/10
 * @twopair
 */
public class RespTest {

	@Test
	public void testResp() {
		SimpleString ok = new SimpleString("OK");
		Errors err = new Errors("ERR");
		RespInt one = new RespInt(1);
		BulkString key = new BulkString(new BytesWrapper("name".getBytes()));
		RespArray array = new RespArray(new Resp[]{key});

	}

	@Test
	public void testGetString() {
		ByteBuf buffer = Unpooled.copiedBuffer("OK\r\n", StandardCharsets.UTF_8);
		System.out.println(buffer.readerIndex());// 0
		System.out.println(buffer.writerIndex());// 4
		String result = Resp.getString(buffer);

		Assert.assertEquals("OK", result);
		Assert.assertEquals(buffer.readerIndex(), buffer.writerIndex());


		System.out.println(buffer.readerIndex());// 4
		System.out.println(buffer.writerIndex());// 4

	}

	@Test
	public void testGetNumber() {
		ByteBuf buffer = Unpooled.copiedBuffer("666\r\n", StandardCharsets.UTF_8);
		long result = Resp.getNumber(buffer);
		Assert.assertEquals(666, result);
		buffer = Unpooled.copiedBuffer("-123\r\n", StandardCharsets.UTF_8);
		result = Resp.getNumber(buffer);
		Assert.assertEquals(-123, result);

	}

}