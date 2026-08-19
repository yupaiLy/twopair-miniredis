package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.*;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * @author ljj
 * @description 实现TYPE命令，返回指定key保存的数据类型。
 * @date 2026/8/19
 * @twopair
 */
public class Type implements Command {

	private BytesWrapper key;

	@Override
	public CommandType type() {
		return CommandType.TYPE;
	}

	/**
	 * 解析TYPE命令参数。
	 *
	 * @param array 命令数组，格式为TYPE key
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("TYPE命令需要key一个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("TYPE的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("TYPE的key不能是NIL");
		}

		this.key = parsedKey;
	}

	/**
	 * 查询key对应的数据类型。
	 *
	 * @param redisCore Redis核心存储
	 * @return string、list、hash、set或none简单字符串
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		RedisData redisData = redisCore.get(key);

		if (redisData == null) {
			return new SimpleString("none");
		}
		if (redisData instanceof RedisString) {
			return new SimpleString("string");
		}
		if (redisData instanceof RedisList) {
			return new SimpleString("list");
		}
		if (redisData instanceof RedisHash) {
			return new SimpleString("hash");
		}
		if (redisData instanceof RedisSet) {
			return new SimpleString("set");
		}

		throw new IllegalStateException("不支持的Redis数据类型: " + redisData.getClass().getName());
	}
}
