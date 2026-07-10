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

	/**
	 * @author ljj
	 * @description 测试数组内的 BulkString 内容被拆包时，解析器会回滚读取位置。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testTryDecodeIncompleteResp() {
		// 模拟第一次 TCP 读取：PING 命令只收到了 "PI"，请求尚不完整。
		ByteBuf buffer = Unpooled.copiedBuffer("*1\r\n$4\r\nPI", StandardCharsets.UTF_8);

		// 记录解析前的位置，后面要验证半包解析不会消耗任何字节。
		int initialReaderIndex = buffer.readerIndex();

		// 调用流式解析入口；半包状态不能产生完整 RESP。
		Resp incomplete = Resp.tryDecode(buffer);

		// 半包不能产生 RESP 对象。
		Assert.assertNull(incomplete);

		// 半包不能推进读取位置，否则下次收到数据时无法从头重新解析。
		Assert.assertEquals(initialReaderIndex, buffer.readerIndex());
		// 模拟第二次 TCP 读取：补齐剩余的 "NG\\r\\n"。
		buffer.writeCharSequence("NG\r\n", StandardCharsets.UTF_8);

		// 数据完整后，应能解析出一个 RESP 数组。
		Resp complete = Resp.tryDecode(buffer);
		Assert.assertTrue(complete instanceof RespArray);

		// PING 命令在 RESP 中是只有一个 BulkString 元素的数组。
		RespArray pingArray = (RespArray) complete;
		Assert.assertEquals(1, pingArray.getArray().length);
		Assert.assertEquals(
				"PING",
				((BulkString) pingArray.getArray()[0]).getBytesWrapper().toUtf8String()
		);
	}

	/**
	 * @author ljj
	 * @description 测试 SimpleString 的 CRLF 被拆包时，解析器会回滚读取位置。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testTryDecodeIncompleteSimpleString() {
		// 模拟第一次 TCP 读取：SimpleString 缺少结尾的 '\n'。
		ByteBuf buffer = Unpooled.copiedBuffer("+OK\r", StandardCharsets.UTF_8);
		int initialReaderIndex = buffer.readerIndex();

		// 半包不能抛出协议错误，而应返回 null 并回滚读取位置。
		Resp incomplete = Resp.tryDecode(buffer);
		Assert.assertNull(incomplete);
		Assert.assertEquals(initialReaderIndex, buffer.readerIndex());

		// 模拟下一次 TCP 读取，补齐 SimpleString 的 CRLF。
		buffer.writeCharSequence("\n", StandardCharsets.UTF_8);

		// 数据完整后，应成功解析出 SimpleString。
		Resp complete = Resp.tryDecode(buffer);
		Assert.assertTrue(complete instanceof SimpleString);
		Assert.assertEquals("OK", ((SimpleString) complete).getContent());
	}

	/**
	 * @author ljj
	 * @description 测试 BulkString 长度行的 CRLF 被拆包时，解析器会回滚读取位置。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testTryDecodeIncompleteBulkStringLength() {
		// 模拟第一次 TCP 读取：BulkString 长度行缺少结尾的 '\n'。
		ByteBuf buffer = Unpooled.copiedBuffer("$4\r", StandardCharsets.UTF_8);
		int initialReaderIndex = buffer.readerIndex();

		// 长度行不完整时，不能误判为非法协议。
		Resp incomplete = Resp.tryDecode(buffer);
		Assert.assertNull(incomplete);
		Assert.assertEquals(initialReaderIndex, buffer.readerIndex());

		// 模拟下一次 TCP 读取：补齐长度行和长度为 4 的内容。
		buffer.writeCharSequence("\nname\r\n", StandardCharsets.UTF_8);

		// 数据完整后，应解析出内容为 name 的 BulkString。
		Resp complete = Resp.tryDecode(buffer);
		Assert.assertTrue(complete instanceof BulkString);
		Assert.assertEquals(
				"name",
				((BulkString) complete).getBytesWrapper().toUtf8String()
		);
	}

	/**
	 * @author ljj
	 * @description 测试空缓冲区属于未接收完整数据，而不是非法 RESP。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testTryDecodeEmptyBuffer() {
		// 模拟网络层尚未收到任何数据的初始状态。
		ByteBuf buffer = Unpooled.buffer();
		int initialReaderIndex = buffer.readerIndex();

		// 空缓冲区不能抛协议异常，应等待后续网络数据。
		Resp incomplete = Resp.tryDecode(buffer);
		Assert.assertNull(incomplete);
		Assert.assertEquals(initialReaderIndex, buffer.readerIndex());

		// 后续数据到达后，应能正常完成解析。
		buffer.writeCharSequence("+OK\r\n", StandardCharsets.UTF_8);
		Resp complete = Resp.tryDecode(buffer);

		Assert.assertTrue(complete instanceof SimpleString);
		Assert.assertEquals("OK", ((SimpleString) complete).getContent());
	}

	/**
	 * @author ljj
	 * @description 测试非法 RESP 类型必须抛出异常，不能被误判为网络半包。
	 * @date 2026/7/10
	 * @twopair
	 */
	@Test
	public void testTryDecodeIllegalResp() {
		// '?' 不是任何合法 RESP 类型前缀。
		ByteBuf buffer = Unpooled.copiedBuffer("?OK\r\n", StandardCharsets.UTF_8);

		try {
			Resp.tryDecode(buffer);
			Assert.fail("预期非法 RESP 类型应抛出 IllegalStateException");
		} catch (IllegalStateException e) {
			// 能进入这里，说明 tryDecode 没有吞掉真正的协议错误。
			Assert.assertNotNull(e);
		}
	}
}