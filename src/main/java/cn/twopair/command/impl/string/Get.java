package cn.twopair.command.impl.string;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.datatype.RedisString;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import io.netty.handler.codec.quic.QuicPathEvent;

/**
 * @author ljj
 * @description
 * @date 2026/7/10
 * @twopair
 */
public class Get implements Command {
	private BytesWrapper key;

	@Override
	public CommandType type() {
		return CommandType.GET;
	}

	@Override
	public void setContent(Resp[] array) {
		this.key = ((BulkString) array[1]).getBytesWrapper();

	}

	/**
	 * Handles the Redis `GET` command, which retrieves the value associated with a given key.
	 * If the key does not exist, returns a NIL bulk string.
	 * Throws an exception if the value associated with the key is not a string.
	 *
	 * @param redisCore the core Redis interface used to interact with the Redis data store
	 * @return a RESP representation of the string value associated with the provided key, or NIL if the key does not exist
	 * @throws IllegalStateException if the value associated with the key is not of type RedisString
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		if(!redisCore.exist(key)) return BulkString.NIL;
		RedisData redisData = redisCore.get(key);
		if(redisData instanceof RedisString){
			return new BulkString(((RedisString) redisData).getValue());
		}
		throw new IllegalStateException("value不是String类型");
	}
}
