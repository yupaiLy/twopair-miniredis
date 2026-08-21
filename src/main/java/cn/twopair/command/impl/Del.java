package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

import java.util.ArrayList;
import java.util.List;

/**
 * 实现Redis的DEL命令，删除一个或多个key。
 *
 * @author ljj
 */
public class Del implements WriteCommand {

	private List<BytesWrapper> keys;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.DEL;
	}

	/**
	 * 解析DEL命令参数。
	 *
	 * @param array 命令数组，格式为DEL key [key ...]
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 2) {
			throw new IllegalArgumentException("DEL命令需要至少一个key参数");
		}

		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString)) {
				throw new IllegalArgumentException("DEL的key必须是BulkString");
			}
		}

		List<BytesWrapper> parsedKeys = new ArrayList<>(array.length - 1);

		for (int i = 1; i < array.length; i++) {
			BytesWrapper key = ((BulkString) array[i]).getBytesWrapper();

			if (key == null || key.getByteArray() == null) {
				throw new IllegalArgumentException("DEL的key不能是NIL");
			}

			parsedKeys.add(key);
		}

		// 所有参数验证成功后再修改命令对象。
		this.keys = parsedKeys;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.delete(keys));
	}
}
