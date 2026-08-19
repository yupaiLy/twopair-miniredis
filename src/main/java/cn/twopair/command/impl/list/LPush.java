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
 * @author ljj
 * @description 实现Redis的LPUSH命令，将元素依次压入列表头部。
 * @date 2026/8/18
 * @twopair
 */
public class LPush implements WriteCommand {

	private BytesWrapper key;
	private List<BytesWrapper> elements;

	@Override
	public CommandType type() {
		return CommandType.LPUSH;
	}

	/**
	 * 解析LPUSH命令参数。
	 *
	 * @param array 命令数组，格式为LPUSH key element [element ...]
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 3) {
			throw new IllegalArgumentException("LPUSH命令需要key和至少一个element参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)) {
			throw new IllegalArgumentException("LPUSH的key必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("LPUSH的key不能是NIL");
		}

		List<BytesWrapper> parsedElements = new ArrayList<>(array.length - 2);

		for (int i = 2; i < array.length; i++) {
			if (!(array[i] instanceof BulkString elementBulkString)) {
				throw new IllegalArgumentException("LPUSH的element必须是BulkString");
			}

			BytesWrapper element = elementBulkString.getBytesWrapper();

			if (element == null || element.getByteArray() == null) {
				throw new IllegalArgumentException("LPUSH的element不能是NIL");
			}

			parsedElements.add(element);
		}

		// 所有参数验证成功后再修改命令对象。
		this.key = parsedKey;
		this.elements = parsedElements;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.leftPush(key, elements));
	}
}