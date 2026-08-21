package cn.twopair.command.impl.hash;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实现Redis的HSET命令，向Hash批量写入字段和值。
 *
 * @author ljj
 */
public class HSet implements WriteCommand {

	private BytesWrapper key;
	private Map<BytesWrapper, BytesWrapper> fields;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.HSET;
	}

	/**
	 * 解析HSET命令参数。
	 *
	 * @param array 命令数组，格式为HSET key field value [field value ...]
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 4 || (array.length - 2) % 2 != 0) {
			throw new IllegalArgumentException("HSET命令需要key和至少一组field、value参数");
		}

		// HSET的全部参数都必须通过RESP Bulk String传输。
		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString)) {
				throw new IllegalArgumentException("HSET的key、field和value必须是BulkString");
			}
		}

		BytesWrapper parsedKey = ((BulkString) array[1]).getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("HSET的key不能是NIL");
		}

		Map<BytesWrapper, BytesWrapper> parsedFields = new LinkedHashMap<>();

		for (int i = 2; i < array.length; i += 2) {
			BytesWrapper field = ((BulkString) array[i]).getBytesWrapper();
			BytesWrapper value = ((BulkString) array[i + 1]).getBytesWrapper();

			if (field == null || field.getByteArray() == null || value == null || value.getByteArray() == null) {
				throw new IllegalArgumentException("HSET的field和value不能是NIL");
			}

			// 重复field会覆盖前面的value，符合HSET最终保留最后一个值的语义。
			parsedFields.put(field, value);
		}

		// 全部参数验证完成后再修改命令对象。
		this.key = parsedKey;
		this.fields = parsedFields;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.putHashFields(key, fields));
	}
}
