package cn.twopair.command.impl.hash;

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
 * @author ljj
 * @description 实现Redis的HDEL命令，删除Hash中的一个或多个字段。
 * @date 2026/8/19
 * @twopair
 */
public class HDel implements WriteCommand {

	private BytesWrapper key;
	private List<BytesWrapper> fields;

	@Override
	public CommandType type() {
		return CommandType.HDEL;
	}

	/**
	 * 解析HDEL命令参数。
	 *
	 * @param array 命令数组，格式为HDEL key field [field ...]
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 3) {
			throw new IllegalArgumentException("HDEL命令需要key和至少一个field参数");
		}

		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString)) {
				throw new IllegalArgumentException("HDEL的key和field必须是BulkString");
			}
		}

		BytesWrapper parsedKey = ((BulkString) array[1]).getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("HDEL的key不能是NIL");
		}

		List<BytesWrapper> parsedFields = new ArrayList<>(array.length - 2);

		for (int i = 2; i < array.length; i++) {
			BytesWrapper field = ((BulkString) array[i]).getBytesWrapper();

			if (field == null || field.getByteArray() == null) {
				throw new IllegalArgumentException("HDEL的field不能是NIL");
			}

			// 保留重复field，第二次删除时自然不会重复计数。
			parsedFields.add(field);
		}

		this.key = parsedKey;
		this.fields = parsedFields;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.hashDelete(key, fields));
	}
}