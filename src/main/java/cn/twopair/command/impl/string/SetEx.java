package cn.twopair.command.impl.string;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * @author ljj
 * @description 实现Redis的SETEX命令，用于写入字符串并同时设置以秒为单位的过期时间。
 * @date 2026/8/11
 * @twopair
 */
public class SetEx implements Command {

	private BytesWrapper key;
	private BytesWrapper value;
	private long seconds;

	@Override
	public CommandType type() {
		return CommandType.SETEX;
	}

	/**
	 * 解析SETEX命令中的key、过期秒数和value。
	 *
	 * @param array SETEX命令对应的RESP数组，格式为SETEX key seconds value
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 4) {
			throw new IllegalArgumentException(
					"SETEX命令需要key、seconds和value三个参数"
			);
		}

		if (!(array[1] instanceof BulkString keyBulkString)
				|| !(array[2] instanceof BulkString secondsBulkString)
				|| !(array[3] instanceof BulkString valueBulkString)) {
			throw new IllegalArgumentException(
					"SETEX的key、seconds和value必须是BulkString"
			);
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		BytesWrapper secondsBytes = secondsBulkString.getBytesWrapper();
		BytesWrapper parsedValue = valueBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("SETEX的key不能是NIL");
		}

		if (secondsBytes == null || secondsBytes.getByteArray() == null) {
			throw new IllegalArgumentException("SETEX的seconds必须是整数");
		}

		if (parsedValue == null || parsedValue.getByteArray() == null) {
			throw new IllegalArgumentException("SETEX的value不能是NIL");
		}

		long parsedSeconds;
		try {
			parsedSeconds = Long.parseLong(secondsBytes.toUtf8String());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"SETEX的seconds必须是整数",
					e
			);
		}

		if (parsedSeconds <= 0L) {
			throw new IllegalArgumentException("SETEX的seconds必须大于0");
		}

		// 所有参数校验通过后再修改对象，避免保存一半有效的状态。
		this.key = parsedKey;
		this.seconds = parsedSeconds;
		this.value = parsedValue;
	}

	/**
	 * 将字符串及其过期时间作为一次操作写入Redis核心存储。
	 *
	 * @param redisCore Redis核心存储对象
	 * @return 执行成功时返回内容为OK的简单字符串
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		RedisString redisString = new RedisString(value);
		redisCore.putWithExpiration(key, redisString, seconds);

		return new SimpleString("OK");
	}
}
