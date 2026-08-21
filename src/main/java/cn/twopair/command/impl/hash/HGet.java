package cn.twopair.command.impl.hash;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

/**
 * 实现Redis的HGET命令，读取Hash中的指定字段。
 *
 * @author ljj
 */
public class HGet implements Command {

	private BytesWrapper key;
	private BytesWrapper field;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.HGET;
	}

	/**
	 * 解析HGET命令参数。
	 *
	 * @param array 命令数组，格式为HGET key field
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 3) {
			throw new IllegalArgumentException("HGET命令需要key和field两个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString) || !(array[2] instanceof BulkString fieldBulkString)) {
			throw new IllegalArgumentException("HGET的key和field必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		BytesWrapper parsedField = fieldBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null || parsedField == null || parsedField.getByteArray() == null) {
			throw new IllegalArgumentException("HGET的key和field不能是NIL");
		}

		this.key = parsedKey;
		this.field = parsedField;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		BytesWrapper value = redisCore.getHashField(key, field);

		// key或field不存在时，Redis返回Null Bulk String。
		if (value == null) {
			return BulkString.NIL;
		}

		return new BulkString(value);
	}
}
