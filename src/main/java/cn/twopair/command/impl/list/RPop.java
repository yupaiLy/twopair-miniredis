package cn.twopair.command.impl.list;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

/**
 * 实现Redis的RPOP命令，从列表尾部弹出一个元素。
 *
 * @author ljj
 */
public class RPop implements WriteCommand {

	private BytesWrapper key;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.RPOP;
	}

	/**
	 * 解析RPOP命令参数。
	 *
	 * @param array 命令数组，格式为RPOP key
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("RPOP命令需要key一个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("RPOP的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("RPOP的key不能是NIL");
		}

		this.key = parsedKey;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		BytesWrapper element = redisCore.rightPop(key);

		if (element == null) {
			return BulkString.NIL;
		}

		return new BulkString(element);
	}
}
