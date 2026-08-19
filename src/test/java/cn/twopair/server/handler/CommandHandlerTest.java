package cn.twopair.server.handler;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisHash;
import cn.twopair.datatype.RedisList;
import cn.twopair.datatype.RedisSet;
import cn.twopair.datatype.RedisString;
import cn.twopair.persistence.aof.AofFile;
import cn.twopair.persistence.aof.AofReplay;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.resp.SimpleString;
import cn.twopair.server.codec.RespDecoder;
import cn.twopair.server.codec.RespEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


/**
 * @author ljj
 * @description Redis 命令业务处理器测试。
 * @date 2026/7/13
 * @twopair
 */
public class CommandHandlerTest {

	/**
	 * @author ljj
	 * @description 测试 PING 经过 CommandHandler 后返回 PONG。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Test
	public void testHandlePing() {
		// CommandHandler 执行命令时需要访问 Redis 核心存储。
		RedisCore redisCore = new RedisCoreImpl();

		/*
		 * Pipeline 注册顺序：
		 * RespEncoder 是出站 Handler，CommandHandler 是入站 Handler。
		 *
		 * CommandHandler 调用 ctx.writeAndFlush() 后，
		 * 出站事件向前传播，经过 RespEncoder。
		 */
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		ByteBuf responseBuffer = null;

		try {
			/*
			 * 本测试不测试 RespDecoder，因此直接构造解码后的 RespArray：
			 *
			 * *1\r\n
			 * $4\r\n
			 * PING\r\n
			 */
			RespArray pingRequest = new RespArray(new Resp[]{
					new BulkString(new BytesWrapper(
							"PING".getBytes(StandardCharsets.UTF_8)
					))
			});

			// 模拟一条 RespArray 入站消息进入 Pipeline。
			channel.writeInbound(pingRequest);

			/*
			 * CommandHandler 写出 SimpleString("PONG")，
			 * RespEncoder 再将它编码成 ByteBuf。
			 */
			responseBuffer = channel.readOutbound();

			Assert.assertNotNull(responseBuffer);
			Assert.assertEquals(
					"+PONG\r\n",
					responseBuffer.toString(StandardCharsets.UTF_8)
			);
		} finally {
			// ByteBuf 使用引用计数管理内存，读取后由测试负责释放。
			if (responseBuffer != null) {
				responseBuffer.release();
			}

			// 释放 EmbeddedChannel 中可能残留的资源。
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试同一 Handler 连续执行 SET 和 GET 时共享 RedisCore。
	 * @date 2026/7/13
	 * @twopair
	 */
	@Test
	public void testHandleSetAndGet() {
		// 同一个 RedisCore 会被注入当前 Channel 的 CommandHandler。
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// 第一次入站：执行 SET name twopair。
			channel.writeInbound(command("SET", "name", "twopair"));

			// SET 返回 SimpleString("OK")，编码结果为 +OK\r\n。
			Assert.assertEquals(
					"+OK\r\n",
					readOutboundAsString(channel)
			);

			// 第二次入站：在同一个 Handler 中执行 GET name。
			channel.writeInbound(command("GET", "name"));

			// twopair 有 7 个 UTF-8 字节，因此返回长度为 7 的 BulkString。
			Assert.assertEquals(
					"$7\r\ntwopair\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试未知命令返回 RESP Error，并且 Channel 可以继续处理命令。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testHandleUnknownCommandAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// UNKNOWN 是合法 RESP 请求，但不是当前支持的 Redis 命令。
			channel.writeInbound(command("UNKNOWN"));

			// CommandHandler 应把业务异常转换成 RESP Error。
			Assert.assertEquals(
					"-ERR 不支持的命令: UNKNOWN\r\n",
					readOutboundAsString(channel)
			);

			// 业务错误不能关闭连接。
			Assert.assertTrue(channel.isOpen());

			// 错误之后再次发送 PING，证明当前连接仍然可以继续使用。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试非 RespArray 请求返回错误，并且 Channel 可以继续使用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNonArrayAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// SimpleString 是合法 RESP 类型，但不是合法的 Redis 命令结构。
			channel.writeInbound(new SimpleString("PING"));

			// Handler 应主动返回 RESP Error，不能让 Java 异常逃出 Pipeline。
			Assert.assertEquals(
					"-ERR 命令必须使用RESP Array\r\n",
					readOutboundAsString(channel)
			);

			// 输入结构错误后，当前连接仍应保持可用。
			Assert.assertTrue(channel.isOpen());

			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试 NIL RespArray 返回错误响应，并且不会关闭连接。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNilArrayAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();

		// 出站事件从后向前传播，因此 RespEncoder 放在 CommandHandler 前面。
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// RespArray.NIL 的外层类型是 RespArray，但内部数组为 null。
			channel.writeInbound(RespArray.NIL);

			Assert.assertEquals(
					"-ERR 命令数组不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 错误命令不应该影响后续正常命令。
			channel.writeInbound(command("PING"));
			Assert.assertEquals("+PONG\r\n", readOutboundAsString(channel));
		} finally {
			// 释放 EmbeddedChannel 中可能残留的引用计数对象。
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试命令名为 NIL BulkString 时返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectNilCommandNameAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * 外层是合法 RespArray，命令名也是 BulkString 类型，
			 * 但是 BulkString.NIL 内部的 BytesWrapper 为 null。
			 */
			RespArray request = new RespArray(new Resp[]{
					BulkString.NIL
			});

			channel.writeInbound(request);

			Assert.assertEquals(
					"-ERR 命令名不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 错误请求不能影响同一连接处理后续正常命令。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试空字符串命令名返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testRejectEmptyCommandNameAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * command("") 构造出的命令名是长度为 0 的 BulkString，
			 * 对应 RESP：*1\r\n$0\r\n\r\n。
			 */
			channel.writeInbound(command(""));

			Assert.assertEquals(
					"-ERR 命令名不能为空\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 空命令名不能影响后续正常请求。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试参数不完整的 SET 返回错误，并保持 Channel 可用。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testHandleMalformedSetAndKeepChannelOpen() {
		RedisCore redisCore = new RedisCoreImpl();
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * SET 正常需要命令名、key、value 三个元素。
			 * 此处缺少 value，Set.setContent() 访问 array[2] 时会越界。
			 */
			channel.writeInbound(command("SET", "name"));

			Assert.assertEquals(
					"-ERR 命令执行失败\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());

			// 单次错误不能导致当前长连接失效。
			channel.writeInbound(command("PING"));
			Assert.assertEquals(
					"+PONG\r\n",
					readOutboundAsString(channel)
			);
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 测试非法 RESP 返回协议错误，并在响应写出后关闭 Channel。
	 * @date 2026/7/14
	 * @twopair
	 */
	@Test
	public void testProtocolErrorResponseAndCloseChannel() {
		RedisCore redisCore = new RedisCoreImpl();

		/*
		 * 这次测试完整入站链路：
		 * ByteBuf -> RespDecoder -> CommandHandler。
		 */
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespDecoder(),
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			/*
			 * '?' 不是任何合法 RESP 类型首字节，
			 * RespDecoder 会抛出协议解析异常。
			 */
			channel.writeInbound(Unpooled.copiedBuffer(
					"?\r\n",
					StandardCharsets.UTF_8
			));

			// 执行可能由 ChannelFutureListener 安排的关闭任务。
			channel.runPendingTasks();

			Assert.assertEquals(
					"-ERR Protocol error: 未知RESP类型: ?\r\n",
					readOutboundAsString(channel)
			);

			// 协议错误后无法可靠判断后续字节边界，因此必须关闭连接。
			Assert.assertFalse(channel.isOpen());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * @author ljj
	 * @description 将 Redis 命令参数构造成 redis-cli 解码后的 RespArray。
	 * @date 2026/7/13
	 * @twopair
	 */
	private RespArray command(String... arguments) {
		Resp[] array = new Resp[arguments.length];

		for (int i = 0; i < arguments.length; i++) {
			// redis-cli 发送的命令名称和参数都使用 BulkString。
			array[i] = new BulkString(new BytesWrapper(
					arguments[i].getBytes(StandardCharsets.UTF_8)
			));
		}

		return new RespArray(array);
	}

	/**
	 * @author ljj
	 * @description 读取并释放 CommandHandler 经过 RespEncoder 产生的出站 ByteBuf。
	 * @date 2026/7/13
	 * @twopair
	 */
	private String readOutboundAsString(EmbeddedChannel channel) {
		ByteBuf responseBuffer = channel.readOutbound();
		Assert.assertNotNull(responseBuffer);

		try {
			return responseBuffer.toString(StandardCharsets.UTF_8);
		} finally {
			// readOutbound 后，ByteBuf 的释放责任属于测试代码。
			responseBuffer.release();
		}
	}
	/**
	 * @author ljj
	 * @description 测试SET、EXPIRE、GET经过CommandHandler后的完整执行链路。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testHandleExpire() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		try {
			// 先写入一个永久存在的字符串。
			channel.writeInbound(command("SET", "name", "twopair"));
			Assert.assertEquals(
					"+OK\r\n",
					readOutboundAsString(channel)
			);

			// 为存在的key设置10秒过期时间，应返回RESP整数1。
			channel.writeInbound(command("EXPIRE", "name", "10"));
			Assert.assertEquals(
					":1\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertEquals(10L, redisCore.ttl(key));

			// 到达过期时间后，GET应返回Null BulkString。
			currentTime.set(11000L);
			channel.writeInbound(command("GET", "name"));
			Assert.assertEquals(
					"$-1\r\n",
					readOutboundAsString(channel)
			);

			// 不存在的key设置过期时间，应返回RESP整数0。
			channel.writeInbound(command("EXPIRE", "missing", "10"));
			Assert.assertEquals(
					":0\r\n",
					readOutboundAsString(channel)
			);

			// 非法seconds应转换成RESP错误，不能关闭连接。
			channel.writeInbound(command("EXPIRE", "name", "abc"));
			Assert.assertEquals(
					"-ERR EXPIRE的seconds必须是整数\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());
		} finally {
			channel.finishAndReleaseAll();
		}
	}
	/**
	 * @author ljj
	 * @description 测试TTL经过CommandHandler后的完整响应。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Test
	public void testHandleTtl() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);

		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			// 不存在的key返回-2。
			channel.writeInbound(command("TTL", "missing"));
			Assert.assertEquals(":-2\r\n", readOutboundAsString(channel));

			// SET创建的key默认永久存在，因此TTL返回-1。
			channel.writeInbound(command("SET", "name", "twopair"));
			Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

			channel.writeInbound(command("TTL", "name"));
			Assert.assertEquals(":-1\r\n", readOutboundAsString(channel));

			// 设置10秒过期时间。
			channel.writeInbound(command("EXPIRE", "name", "10"));
			Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

			channel.writeInbound(command("TTL", "name"));
			Assert.assertEquals(":10\r\n", readOutboundAsString(channel));

			// 经过1秒后剩余9秒。
			currentTime.set(2000L);
			channel.writeInbound(command("TTL", "name"));
			Assert.assertEquals(":9\r\n", readOutboundAsString(channel));

			// 到达过期时间后返回-2。
			currentTime.set(11000L);
			channel.writeInbound(command("TTL", "name"));
			Assert.assertEquals(":-2\r\n", readOutboundAsString(channel));

			// 缺少key时返回RESP错误，但不关闭连接。
			channel.writeInbound(command("TTL"));
			Assert.assertEquals(
					"-ERR TTL命令需要key一个参数\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * 验证SETEX经过命令处理器后能够写入数据、设置TTL并按时过期。
	 */
	@Test
	public void testHandleSetEx() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		EmbeddedChannel channel = new EmbeddedChannel(
				new RespEncoder(),
				new CommandHandler(redisCore)
		);

		try {
			channel.writeInbound(command("SETEX", "name", "10", "twopair"));
			Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

			channel.writeInbound(command("TTL", "name"));
			Assert.assertEquals(":10\r\n", readOutboundAsString(channel));

			channel.writeInbound(command("GET", "name"));
			Assert.assertEquals("$7\r\ntwopair\r\n", readOutboundAsString(channel));

			currentTime.set(11000L);
			channel.writeInbound(command("GET", "name"));
			Assert.assertEquals("$-1\r\n", readOutboundAsString(channel));

			channel.writeInbound(command("SETEX", "name", "abc", "value"));
			Assert.assertEquals(
					"-ERR SETEX的seconds必须是整数\r\n",
					readOutboundAsString(channel)
			);
			Assert.assertTrue(channel.isOpen());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	/**
	 * 验证命令处理器只会把执行成功的写命令追加到AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testPersistOnlySuccessfulWriteCommands() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-handler-", ".aof");

		try {
			RedisCore redisCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(
						new RespEncoder(),
						new CommandHandler(redisCore, aofFile)
				);

				try {
					channel.writeInbound(command("PING"));
					Assert.assertEquals("+PONG\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("GET", "name"));
					Assert.assertEquals(
							"$7\r\ntwopair\r\n",
							readOutboundAsString(channel)
					);

					// 参数不完整的SET执行失败，不能污染AOF。
					channel.writeInbound(command("SET", "broken"));
					Assert.assertEquals(
							"-ERR 命令执行失败\r\n",
							readOutboundAsString(channel)
					);
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String expected = "*3\r\n"
					+ "$3\r\nSET\r\n"
					+ "$4\r\nname\r\n"
					+ "$7\r\ntwopair\r\n";

			Assert.assertArrayEquals(
					expected.getBytes(StandardCharsets.UTF_8),
					Files.readAllBytes(path)
			);
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证SETEX会以SET和绝对时间PEXPIREAT写入AOF，重放后不会延长TTL。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testPersistSetExWithAbsoluteExpiration() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-setex-", ".aof");
		AtomicLong writeTime = new AtomicLong(1000L);

		try {
			RedisCore sourceCore = new RedisCoreImpl(writeTime::get);

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(
						new RespEncoder(),
						new CommandHandler(sourceCore, aofFile)
				);

				try {
					channel.writeInbound(command("SETEX", "name", "10", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("SET\r\n"));
			Assert.assertTrue(aofContent.contains("PEXPIREAT\r\n"));
			Assert.assertFalse(aofContent.contains("SETEX\r\n"));

			AtomicLong replayTime = new AtomicLong(5000L);
			RedisCore restoredCore = new RedisCoreImpl(replayTime::get);
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));

			BytesWrapper key = new BytesWrapper(
					"name".getBytes(StandardCharsets.UTF_8)
			);
			RedisData restoredData = restoredCore.get(key);
			Assert.assertTrue(restoredData instanceof RedisString);
			Assert.assertEquals(
					"twopair",
					((RedisString) restoredData).getValue().toUtf8String()
			);
			Assert.assertEquals(6L, restoredCore.ttl(key));

			replayTime.set(11000L);
			Assert.assertNull(restoredCore.get(key));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证多个连接并发写同一个key时，内存执行顺序与AOF记录顺序保持一致。
	 *
	 * @throws Exception 当线程等待、临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testKeepMemoryAndAofWriteOrderConsistent() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-order-", ".aof");
		CountDownLatch firstWriteApplied = new CountDownLatch(1);
		CountDownLatch allowFirstWriteToReturn = new CountDownLatch(1);
		CountDownLatch secondCommandCompleted = new CountDownLatch(1);
		AtomicReference<Throwable> threadFailure = new AtomicReference<>();

		RedisCoreImpl sourceCore = new RedisCoreImpl() {
			@Override
			public void put(BytesWrapper key, RedisData value) {
				super.put(key, value);

				if (value instanceof RedisString redisString
						&& "first".equals(
						redisString.getValue().toUtf8String()
				)) {
					firstWriteApplied.countDown();

					try {
						allowFirstWriteToReturn.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("测试线程被中断", e);
					}
				}
			}
		};

		try (AofFile aofFile = new AofFile(path)) {
			EmbeddedChannel firstChannel = new EmbeddedChannel(
					new RespEncoder(),
					new CommandHandler(sourceCore, aofFile)
			);
			EmbeddedChannel secondChannel = new EmbeddedChannel(
					new RespEncoder(),
					new CommandHandler(sourceCore, aofFile)
			);

			Thread firstThread = new Thread(() -> {
				try {
					firstChannel.writeInbound(
							command("SET", "name", "first")
					);
				} catch (Throwable e) {
					threadFailure.compareAndSet(null, e);
				}
			}, "first-aof-writer");

			Thread secondThread = new Thread(() -> {
				try {
					secondChannel.writeInbound(
							command("SET", "name", "second")
					);
				} catch (Throwable e) {
					threadFailure.compareAndSet(null, e);
				} finally {
					secondCommandCompleted.countDown();
				}
			}, "second-aof-writer");

			try {
				firstThread.start();
				Assert.assertTrue(firstWriteApplied.await(1, TimeUnit.SECONDS));

				secondThread.start();
				/*
				 * 修复前第二条命令会越过第一条并先写AOF；
				 * 修复后它会等待第一条完成，因此这里允许短暂等待后统一放行。
				 */
				secondCommandCompleted.await(200, TimeUnit.MILLISECONDS);
				allowFirstWriteToReturn.countDown();

				firstThread.join(1000L);
				secondThread.join(1000L);
				Assert.assertFalse(firstThread.isAlive());
				Assert.assertFalse(secondThread.isAlive());
				Assert.assertNull(threadFailure.get());
			} finally {
				allowFirstWriteToReturn.countDown();
				firstChannel.finishAndReleaseAll();
				secondChannel.finishAndReleaseAll();
			}
		}

		try {
			BytesWrapper key = new BytesWrapper(
					"name".getBytes(StandardCharsets.UTF_8)
			);
			RedisString memoryValue = (RedisString) sourceCore.get(key);

			RedisCore restoredCore = new RedisCoreImpl();
			AofReplay.replay(path, restoredCore);
			RedisString restoredValue = (RedisString) restoredCore.get(key);

			Assert.assertEquals(
					memoryValue.getValue().toUtf8String(),
					restoredValue.getValue().toUtf8String()
			);
			Assert.assertEquals(
					"second",
					restoredValue.getValue().toUtf8String()
			);
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证LPUSH网络响应、WRONGTYPE错误和AOF重放形成完整闭环。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleLPushAndWrongTypeWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-lpush-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("LPUSH", "letters", "one", "two", "三"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LPUSH", "name", "invalid"));
					Assert.assertEquals(
							"-WRONGTYPE Operation against a key holding the wrong kind of value\r\n",
							readOutboundAsString(channel)
					);
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("LPUSH"));
			Assert.assertFalse(aofContent.contains("invalid"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			RedisList restoredList = (RedisList) restoredCore.get(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8)));
			Assert.assertEquals("三", restoredList.leftPop().toUtf8String());
			Assert.assertEquals("two", restoredList.leftPop().toUtf8String());
			Assert.assertEquals("one", restoredList.leftPop().toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证LPOP的元素响应、空列表响应、WRONGTYPE错误和AOF重放。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleLPopWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-lpop-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("LPUSH", "letters", "one", "two"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LPOP", "letters"));
					Assert.assertEquals("$3\r\ntwo\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LPOP", "letters"));
					Assert.assertEquals("$3\r\none\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LPOP", "letters"));
					Assert.assertEquals("$-1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LPOP", "name"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("LPOP"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(5, AofReplay.replay(path, restoredCore));
			Assert.assertNull(restoredCore.get(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8))));
			RedisString restoredString = (RedisString) restoredCore.get(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)));
			Assert.assertEquals("twopair", restoredString.getValue().toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证LLEN返回列表长度、保持WRONGTYPE连接可用，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleLLenWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-llen-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("LPUSH", "letters", "one", "two", "三"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LLEN", "letters"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LLEN", "missing"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LLEN", "name"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("LLEN"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertEquals(3L, restoredCore.listLength(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8))));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证LRANGE的数组响应、越界规则、WRONGTYPE错误，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleLRangeWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-lrange-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("LPUSH", "letters", "one", "two", "三"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LRANGE", "letters", "0", "1"));
					Assert.assertEquals("*2\r\n$3\r\n三\r\n$3\r\ntwo\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LRANGE", "letters", "3", "100"));
					Assert.assertEquals("*0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("LRANGE", "name", "0", "-1"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("LRANGE"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertEquals(3L, restoredCore.listLength(new BytesWrapper("letters".getBytes(StandardCharsets.UTF_8))));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证HSET的新增计数、覆盖语义、WRONGTYPE错误和AOF重放。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleHSetWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-hset-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("HSET", "user:1", "name", "twopair", "city", "杭州"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HSET", "user:1", "name", "老板", "age", "18"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HSET", "name", "field", "should-not-persist"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("HSET"));
			Assert.assertFalse(aofContent.contains("should-not-persist"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(3, AofReplay.replay(path, restoredCore));
			RedisHash restoredHash = (RedisHash) restoredCore.get(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8)));
			Assert.assertEquals("老板", restoredHash.get(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))).toUtf8String());
			Assert.assertEquals("杭州", restoredHash.get(new BytesWrapper("city".getBytes(StandardCharsets.UTF_8))).toUtf8String());
			Assert.assertEquals("18", restoredHash.get(new BytesWrapper("age".getBytes(StandardCharsets.UTF_8))).toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证HGET的字段响应、NIL响应、WRONGTYPE错误，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleHGetWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-hget-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("HSET", "user:1", "name", "老板"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HGET", "user:1", "name"));
					Assert.assertEquals("$6\r\n老板\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HGET", "user:1", "missing"));
					Assert.assertEquals("$-1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HGET", "missing", "name"));
					Assert.assertEquals("$-1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HGET", "name", "field"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("HGET"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertEquals("老板", restoredCore.getHashField(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8)), new BytesWrapper("name".getBytes(StandardCharsets.UTF_8))).toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证HDEL的删除计数、空Hash删key、WRONGTYPE错误和AOF重放。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleHDelWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-hdel-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("HSET", "user:1", "name", "老板", "city", "杭州", "age", "18"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HDEL", "user:1", "city", "missing"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HDEL", "user:1", "name", "age"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HDEL", "user:1", "name"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HDEL", "name", "field"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("HDEL"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(5, AofReplay.replay(path, restoredCore));
			Assert.assertNull(restoredCore.get(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8))));
			Assert.assertEquals("twopair", ((RedisString) restoredCore.get(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))).getValue().toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证HLEN的字段数量、WRONGTYPE错误，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleHLenWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-hlen-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("HSET", "user:1", "name", "老板", "city", "杭州"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HLEN", "user:1"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HLEN", "missing"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("HLEN", "name"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("HLEN"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertEquals(2L, restoredCore.getHashSize(new BytesWrapper("user:1".getBytes(StandardCharsets.UTF_8))));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证SADD的新增计数、成员去重、WRONGTYPE错误和AOF重放。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleSAddWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-sadd-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("SADD", "tags", "java", "redis", "中文", "java"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SADD", "tags", "redis", "netty"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SADD", "name", "should-not-persist"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("SADD"));
			Assert.assertFalse(aofContent.contains("should-not-persist"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(3, AofReplay.replay(path, restoredCore));
			RedisSet restoredSet = (RedisSet) restoredCore.get(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8)));
			Assert.assertTrue(restoredSet.contains(new BytesWrapper("java".getBytes(StandardCharsets.UTF_8))));
			Assert.assertTrue(restoredSet.contains(new BytesWrapper("中文".getBytes(StandardCharsets.UTF_8))));
			Assert.assertTrue(restoredSet.contains(new BytesWrapper("netty".getBytes(StandardCharsets.UTF_8))));
			Assert.assertEquals(4L, restoredSet.size());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证SREM的删除计数、空Set删key、WRONGTYPE错误和AOF重放。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleSRemWithAof() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-srem-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("SADD", "tags", "java", "redis", "中文"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SREM", "tags", "redis", "missing"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SREM", "tags", "java", "中文"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SREM", "tags", "java"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SREM", "name", "should-not-persist"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertTrue(aofContent.contains("SREM"));
			Assert.assertFalse(aofContent.contains("should-not-persist"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(5, AofReplay.replay(path, restoredCore));
			Assert.assertNull(restoredCore.get(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8))));
			Assert.assertEquals("twopair", ((RedisString) restoredCore.get(new BytesWrapper("name".getBytes(StandardCharsets.UTF_8)))).getValue().toUtf8String());
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证SISMEMBER的存在性响应、WRONGTYPE错误，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleSIsMemberWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-sismember-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("SADD", "tags", "java", "中文"));
					Assert.assertEquals(":2\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SISMEMBER", "tags", "中文"));
					Assert.assertEquals(":1\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SISMEMBER", "tags", "missing"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SISMEMBER", "missing", "java"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SISMEMBER", "name", "member"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("SISMEMBER"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertTrue(restoredCore.containsSetMember(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8)), new BytesWrapper("中文".getBytes(StandardCharsets.UTF_8))));
		} finally {
			Files.deleteIfExists(path);
		}
	}

	/**
	 * 验证SCARD的成员数量、WRONGTYPE错误，并且不会写入AOF。
	 *
	 * @throws Exception 当临时文件或AOF读写失败时抛出
	 */
	@Test
	public void testHandleSCardWithoutAofAppend() throws Exception {
		Path path = Files.createTempFile("twopair-miniredis-scard-", ".aof");

		try {
			RedisCore sourceCore = new RedisCoreImpl();

			try (AofFile aofFile = new AofFile(path)) {
				EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(sourceCore, aofFile));

				try {
					channel.writeInbound(command("SADD", "tags", "java", "redis", "中文", "java"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SCARD", "tags"));
					Assert.assertEquals(":3\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SCARD", "missing"));
					Assert.assertEquals(":0\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SET", "name", "twopair"));
					Assert.assertEquals("+OK\r\n", readOutboundAsString(channel));

					channel.writeInbound(command("SCARD", "name"));
					Assert.assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", readOutboundAsString(channel));
					Assert.assertTrue(channel.isOpen());
				} finally {
					channel.finishAndReleaseAll();
				}
			}

			String aofContent = Files.readString(path, StandardCharsets.UTF_8);
			Assert.assertFalse(aofContent.contains("SCARD"));

			RedisCore restoredCore = new RedisCoreImpl();
			Assert.assertEquals(2, AofReplay.replay(path, restoredCore));
			Assert.assertEquals(3L, restoredCore.getSetSize(new BytesWrapper("tags".getBytes(StandardCharsets.UTF_8))));
		} finally {
			Files.deleteIfExists(path);
		}
	}
}
