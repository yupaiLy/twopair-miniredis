package cn.twopair.command.impl.set;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

/**
 * @author ljj
 * @description 实现Redis的SCARD命令，获取Set包含的唯一成员数量。
 * @date 2026/8/19
 * @twopair
 */
public class SCard implements Command {

	private BytesWrapper key;

	@Override
	public CommandType type() {
		return CommandType.SCARD;
	}

	/**
	 * 解析SCARD命令参数。
	 *
	 * @param array 命令数组，格式为SCARD key
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("SCARD命令需要key一个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("SCARD的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("SCARD的key不能是NIL");
		}

		this.key = parsedKey;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.getSetSize(key));
	}
}