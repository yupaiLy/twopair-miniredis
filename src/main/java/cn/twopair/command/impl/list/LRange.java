package cn.twopair.command.impl.list;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;

import java.util.List;

/**
 * @author ljj
 * @description 实现Redis的LRANGE命令，查询列表指定范围内的元素。
 * @date 2026/8/18
 * @twopair
 */
public class LRange implements Command {

	private BytesWrapper key;
	private long start;
	private long stop;

	@Override
	public CommandType type() {
		return CommandType.LRANGE;
	}

	/**
	 * 解析LRANGE命令参数。
	 *
	 * @param array 命令数组，格式为LRANGE key start stop
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 4) {
			throw new IllegalArgumentException("LRANGE命令需要key、start和stop三个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString)
				|| !(array[2] instanceof BulkString startBulkString)
				|| !(array[3] instanceof BulkString stopBulkString)) {
			throw new IllegalArgumentException("LRANGE的key、start和stop必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		BytesWrapper startBytes = startBulkString.getBytesWrapper();
		BytesWrapper stopBytes = stopBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("LRANGE的key不能是NIL");
		}

		if (startBytes == null || startBytes.getByteArray() == null || stopBytes == null || stopBytes.getByteArray() == null) {
			throw new IllegalArgumentException("LRANGE的start和stop必须是整数");
		}

		long parsedStart;
		long parsedStop;

		try {
			parsedStart = Long.parseLong(startBytes.toUtf8String());
			parsedStop = Long.parseLong(stopBytes.toUtf8String());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("LRANGE的start和stop必须是整数", e);
		}

		// 所有参数验证成功后再保存，避免命令对象处于半初始化状态。
		this.key = parsedKey;
		this.start = parsedStart;
		this.stop = parsedStop;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		List<BytesWrapper> elements = redisCore.listRange(key, start, stop);
		Resp[] response = new Resp[elements.size()];

		for (int i = 0; i < elements.size(); i++) {
			response[i] = new BulkString(elements.get(i));
		}

		return new RespArray(response);
	}
}