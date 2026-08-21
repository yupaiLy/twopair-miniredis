package cn.twopair.command.impl.string;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * 实现Redis的SET命令，写入String类型的值。
 *
 * @author ljj
 */
public class Set implements WriteCommand {

	private BytesWrapper key;
	private BytesWrapper value;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.SET;
	}

	/**
	 * 解析SET命令参数。
	 *
	 * <p>当前实现直接读取key和value，尚未做参数校验。
	 *
	 * @param array 命令数组，格式为SET key value
	 */
	@Override
	public void setContent(Resp[] array) {
		this.key = ((BulkString) array[1]).getBytesWrapper();
		this.value = ((BulkString) array[2]).getBytesWrapper();
	}

	/**
	 * 将key和value写入Redis核心存储，并清除已有的过期时间。
	 *
	 * @param redisCore Redis核心存储
	 * @return 内容为 {@code OK} 的简单字符串
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		RedisString redisString = new RedisString(value);
		redisString.setTimeout(-1);
		redisCore.put(key, redisString);
		return new SimpleString("OK");
	}
}
