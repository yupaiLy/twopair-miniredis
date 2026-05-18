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
		int result = Resp.getNumber(buffer);
		Assert.assertEquals(666, result);
		buffer = Unpooled.copiedBuffer("-123\r\n", StandardCharsets.UTF_8);
		result = Resp.getNumber(buffer);
		Assert.assertEquals(-123, result);

	}

	@Test
	public void testDecode() {
		// 1. 测试 SimpleString
		ByteBuf simpleBuffer = Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8);
		SimpleString simpleString = (SimpleString) Resp.decode(simpleBuffer);
		Assert.assertEquals("OK", simpleString.getContent());

		// 2. 测试 Errors
		ByteBuf errorBuffer = Unpooled.copiedBuffer("-ERR unknown command\r\n", StandardCharsets.UTF_8);
		Errors errors = (Errors) Resp.decode(errorBuffer);
		Assert.assertEquals("ERR unknown command", errors.getContent());

		// 3. 测试 RespInt
		ByteBuf intBuffer = Unpooled.copiedBuffer(":100\r\n", StandardCharsets.UTF_8);
		RespInt respInt = (RespInt) Resp.decode(intBuffer);
		Assert.assertEquals(100, respInt.getValue());

		// 4. 测试普通 BulkString
		ByteBuf bulkBuffer = Unpooled.copiedBuffer("$4\r\nname\r\n", StandardCharsets.UTF_8);
		BulkString bulkString = (BulkString) Resp.decode(bulkBuffer);
		Assert.assertEquals("name", bulkString.getBytesWrapper().toUtf8String());

		// 5. 测试空 BulkString
		ByteBuf emptyBulkBuffer = Unpooled.copiedBuffer("$0\r\n\r\n", StandardCharsets.UTF_8);
		BulkString emptyBulkString = (BulkString) Resp.decode(emptyBulkBuffer);
		Assert.assertEquals("", emptyBulkString.getBytesWrapper().toUtf8String());

		// 6. 测试 Null BulkString
		ByteBuf nullBulkBuffer = Unpooled.copiedBuffer("$-1\r\n", StandardCharsets.UTF_8);
		BulkString nullBulkString = (BulkString) Resp.decode(nullBulkBuffer);
		Assert.assertSame(BulkString.NIL, nullBulkString);

		// 7. 测试 RespArray
		ByteBuf arrayBuffer = Unpooled.copiedBuffer(
				"*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\ntwopair\r\n",
				StandardCharsets.UTF_8
		);
		RespArray respArray = (RespArray) Resp.decode(arrayBuffer);
		Assert.assertEquals(3, respArray.getArray().length);
		Assert.assertEquals("SET", ((BulkString) respArray.getArray()[0]).getBytesWrapper().toUtf8String());
		Assert.assertEquals("name", ((BulkString) respArray.getArray()[1]).getBytesWrapper().toUtf8String());
		Assert.assertEquals("twopair", ((BulkString) respArray.getArray()[2]).getBytesWrapper().toUtf8String());

		// 8. 测试不完整 BulkString 应抛异常
		try {
			ByteBuf incompleteBulkBuffer = Unpooled.copiedBuffer("$4\r\nna", StandardCharsets.UTF_8);
			Resp.decode(incompleteBulkBuffer);
			Assert.fail("预期应该抛出 IllegalStateException，但没有抛出");
		} catch (IllegalStateException e) {
			Assert.assertNotNull(e);
		}
	}

	@Test
	public void testEncode() {
		ByteBuf buffer;

		// 1. SimpleString
		buffer = Unpooled.buffer();
		Resp.encode(new SimpleString("OK"), buffer);
		Assert.assertEquals("+OK\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 2. Errors
		buffer = Unpooled.buffer();
		Resp.encode(new Errors("ERR unknown command"), buffer);
		Assert.assertEquals("-ERR unknown command\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 3. RespInt
		buffer = Unpooled.buffer();
		Resp.encode(new RespInt(100), buffer);
		Assert.assertEquals(":100\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 4. BulkString
		buffer = Unpooled.buffer();
		Resp.encode(new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))), buffer);
		Assert.assertEquals("$4\r\nname\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 5. Empty BulkString
		buffer = Unpooled.buffer();
		Resp.encode(new BulkString(new BytesWrapper("".getBytes(StandardCharsets.UTF_8))), buffer);
		Assert.assertEquals("$0\r\n\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 6. Null BulkString
		buffer = Unpooled.buffer();
		Resp.encode(BulkString.NIL, buffer);
		Assert.assertEquals("$-1\r\n", buffer.toString(StandardCharsets.UTF_8));

		// 7. RespArray
		buffer = Unpooled.buffer();
		Resp.encode(new RespArray(new Resp[]{
				new BulkString(new BytesWrapper("SET".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))),
				new BulkString(new BytesWrapper("twopair".getBytes(StandardCharsets.UTF_8)))
		}), buffer);
		Assert.assertEquals("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\ntwopair\r\n",
				buffer.toString(StandardCharsets.UTF_8));
	}


}