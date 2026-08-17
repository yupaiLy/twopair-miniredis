package cn.twopair.command.impl.string;

import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试SETEX字符串命令的行为。
 */
public class SetExTest {

	/**
	 * 验证SETEX能够写入字符串、设置过期时间并返回OK。
	 */
	@Test
	public void testSetExStoresValueWithExpiration() {
		AtomicLong currentTime = new AtomicLong(1000L);
		RedisCore redisCore = new RedisCoreImpl(currentTime::get);
		BytesWrapper key = new BytesWrapper(
				"name".getBytes(StandardCharsets.UTF_8)
		);

		SetEx setEx = new SetEx();
		setEx.setContent(new Resp[]{
				new BulkString(new BytesWrapper(
						"SETEX".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(key),
				new BulkString(new BytesWrapper(
						"10".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(new BytesWrapper(
						"twopair".getBytes(StandardCharsets.UTF_8)
				))
		});

		Assert.assertEquals(CommandType.SETEX, setEx.type());
		Resp response = setEx.handle(redisCore);
		Assert.assertTrue(response instanceof SimpleString);
		Assert.assertEquals("OK", ((SimpleString) response).getContent());

		RedisData data = redisCore.get(key);
		Assert.assertTrue(data instanceof RedisString);
		Assert.assertEquals(
				"twopair",
				((RedisString) data).getValue().toUtf8String()
		);
		Assert.assertEquals(10L, redisCore.ttl(key));

		currentTime.set(11000L);
		Assert.assertNull(redisCore.get(key));
	}

	/**
	 * 验证SETEX缺少参数时会抛出含义明确的参数异常。
	 */
	@Test
	public void testSetExRejectsWrongArgumentCount() {
		SetEx setEx = new SetEx();

		try {
			setEx.setContent(new Resp[]{
					new BulkString(new BytesWrapper(
							"SETEX".getBytes(StandardCharsets.UTF_8)
					)),
					new BulkString(new BytesWrapper(
							"name".getBytes(StandardCharsets.UTF_8)
					))
			});
			Assert.fail("SETEX缺少参数时应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(
					"SETEX命令需要key、seconds和value三个参数",
					e.getMessage()
			);
		}
	}

	/**
	 * 验证SETEX的key、seconds和value必须使用BulkString表示。
	 */
	@Test
	public void testSetExRejectsNonBulkStringArguments() {
		SetEx setEx = new SetEx();

		try {
			setEx.setContent(new Resp[]{
					new BulkString(new BytesWrapper(
							"SETEX".getBytes(StandardCharsets.UTF_8)
					)),
					new SimpleString("name"),
					new BulkString(new BytesWrapper(
							"10".getBytes(StandardCharsets.UTF_8)
					)),
					new BulkString(new BytesWrapper(
							"twopair".getBytes(StandardCharsets.UTF_8)
					))
			});
			Assert.fail("SETEX参数不是BulkString时应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(
					"SETEX的key、seconds和value必须是BulkString",
					e.getMessage()
			);
		}
	}

	/**
	 * 验证SETEX会拒绝NIL参数、非法秒数以及非正数过期时间。
	 */
	@Test
	public void testSetExRejectsInvalidValues() {
		assertSetContentError(
				new Resp[]{bulk("SETEX"), BulkString.NIL, bulk("10"), bulk("twopair")},
				"SETEX的key不能是NIL"
		);
		assertSetContentError(
				new Resp[]{bulk("SETEX"), bulk("name"), BulkString.NIL, bulk("twopair")},
				"SETEX的seconds必须是整数"
		);
		assertSetContentError(
				new Resp[]{bulk("SETEX"), bulk("name"), bulk("10"), BulkString.NIL},
				"SETEX的value不能是NIL"
		);
		assertSetContentError(
				new Resp[]{bulk("SETEX"), bulk("name"), bulk("abc"), bulk("twopair")},
				"SETEX的seconds必须是整数"
		);
		assertSetContentError(
				new Resp[]{bulk("SETEX"), bulk("name"), bulk("0"), bulk("twopair")},
				"SETEX的seconds必须大于0"
		);
		assertSetContentError(
				new Resp[]{bulk("SETEX"), bulk("name"), bulk("-1"), bulk("twopair")},
				"SETEX的seconds必须大于0"
		);
	}

	/**
	 * 断言SETEX解析参数时抛出指定的参数异常。
	 *
	 * @param array SETEX命令对应的RESP数组
	 * @param expectedMessage 预期的异常信息
	 */
	private void assertSetContentError(Resp[] array, String expectedMessage) {
		try {
			new SetEx().setContent(array);
			Assert.fail("SETEX非法参数应抛出IllegalArgumentException");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(expectedMessage, e.getMessage());
		}
	}

	/**
	 * 将字符串参数转换成RESP块字符串。
	 *
	 * @param value 字符串参数
	 * @return RESP块字符串
	 */
	private BulkString bulk(String value) {
		return new BulkString(new BytesWrapper(
				value.getBytes(StandardCharsets.UTF_8)
		));
	}
}
