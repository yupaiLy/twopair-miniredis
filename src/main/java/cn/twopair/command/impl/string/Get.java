package cn.twopair.command.impl.string;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

/**
 * 实现Redis的GET命令，读取String类型的值。
 *
 * @author ljj
 */
public class Get implements Command {
	private BytesWrapper key;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.GET;
	}

	/**
	 * 解析GET命令参数。
	 *
	 * @param array 命令数组，格式为GET key
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("GET命令需要key一个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("GET的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("GET的key不能是NIL");
		}

		this.key = parsedKey;
	}

	/**
	 * 读取key对应的String值。
	 *
	 * @param redisCore Redis核心存储
	 * @return 字符串值；key不存在时返回 {@link BulkString#NIL 空块字符串}
	 * @throws WrongTypeException key存在但不是String时抛出
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		RedisData redisData = redisCore.get(key);

		// key不存在（或读取瞬间刚好过期）时返回NIL，与Redis语义一致。
		if (redisData == null) {
			return BulkString.NIL;
		}

		if (!(redisData instanceof RedisString redisString)) {
			throw new WrongTypeException();
		}

		return new BulkString(redisString.getValue());
	}
}
