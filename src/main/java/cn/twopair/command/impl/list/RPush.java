package cn.twopair.command.impl.list;

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
 * 实现Redis的RPUSH命令，将元素依次压入列表尾部。
 *
 * @author ljj
 */
public class RPush implements WriteCommand {

	private BytesWrapper key;
	private List<BytesWrapper> elements;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.RPUSH;
	}

	/**
	 * 解析RPUSH命令参数。
	 *
	 * @param array 命令数组，格式为RPUSH key element [element ...]
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 3) {
			throw new IllegalArgumentException("RPUSH命令需要key和至少一个element参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("RPUSH的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("RPUSH的key不能是NIL");
		}

		List<BytesWrapper> parsedElements = new ArrayList<>(array.length - 2);

		for (int i = 2; i < array.length; i++) {
			if (!(array[i] instanceof BulkString elementBulkString)) {
				throw new IllegalArgumentException("RPUSH的element必须是BulkString");
			}

			BytesWrapper element = elementBulkString.getBytesWrapper();

			if (element == null || element.getByteArray() == null) {
				throw new IllegalArgumentException("RPUSH的element不能是NIL");
			}

			parsedElements.add(element);
		}

		// 所有参数验证成功后再修改命令对象。
		this.key = parsedKey;
		this.elements = parsedElements;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.rightPush(key, elements));
	}
}
