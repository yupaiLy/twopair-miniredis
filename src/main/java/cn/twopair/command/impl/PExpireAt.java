package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

/**
 * @author ljj
 * @description 实现PEXPIREAT命令，为key设置绝对毫秒过期时间。
 * @date 2026/8/18
 * @twopair
 */
public class PExpireAt implements WriteCommand {

	private BytesWrapper key;
	private long expireAtMillis;

	@Override
	public CommandType type() {
		return CommandType.PEXPIREAT;
	}

	/**
	 * 解析PEXPIREAT命令参数。
	 *
	 * @param array 命令数组，格式为PEXPIREAT key milliseconds
	 * @throws IllegalArgumentException 当参数不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 3) {
			throw new IllegalArgumentException(
					"PEXPIREAT命令需要key和milliseconds两个参数"
			);
		}

		if (!(array[1] instanceof BulkString keyBulkString)
				|| !(array[2] instanceof BulkString timeBulkString)) {
			throw new IllegalArgumentException(
					"PEXPIREAT的key和milliseconds必须是BulkString"
			);
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		BytesWrapper timeBytes = timeBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("PEXPIREAT的key不能是NIL");
		}

		if (timeBytes == null || timeBytes.getByteArray() == null) {
			throw new IllegalArgumentException(
					"PEXPIREAT的milliseconds必须是整数"
			);
		}

		long parsedExpireAt;

		try {
			parsedExpireAt = Long.parseLong(timeBytes.toUtf8String());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"PEXPIREAT的milliseconds必须是整数",
					e
			);
		}

		this.key = parsedKey;
		this.expireAtMillis = parsedExpireAt;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		boolean success = redisCore.expireAt(key, expireAtMillis);
		return new RespInt(success ? 1L : 0L);
	}
}
