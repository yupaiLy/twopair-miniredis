package cn.twopair.command.impl.list;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

/**
 * 实现Redis的LLEN命令，获取列表包含的元素数量。
 *
 * @author ljj
 */
public class LLen implements Command {

	private BytesWrapper key;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.LLEN;
	}

	/**
	 * 解析LLEN命令参数。
	 *
	 * @param array 命令数组，格式为LLEN key
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("LLEN命令需要key一个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("LLEN的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("LLEN的key不能是NIL");
		}

		this.key = parsedKey;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.listLength(key));
	}
}
