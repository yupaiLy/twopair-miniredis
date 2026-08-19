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
 * @author ljj
 * @description
 * @date 2026/7/10
 * @twopair
 */
public class Set implements WriteCommand {

	private BytesWrapper key;
	private BytesWrapper value;

	@Override
	public CommandType type() {
		return CommandType.SET;
	}

	@Override
	public void setContent(Resp[] array) {
		this.key = ((BulkString) array[1]).getBytesWrapper();
		this.value = ((BulkString) array[2]).getBytesWrapper();
	}

	/**
	 * Handles the SET command by storing the provided key-value pair in the Redis core.
	 *
	 * @param redisCore The core storage of the Redis server where the key-value pair will be stored.
	 * @return A RESP simple string response confirming the operation with "OK".
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		RedisString redisString = new RedisString(value);
		redisString.setTimeout(-1);
		redisCore.put(key, redisString);
		return new SimpleString("OK");
	}
}
