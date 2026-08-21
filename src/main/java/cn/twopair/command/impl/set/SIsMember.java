package cn.twopair.command.impl.set;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespInt;

/**
 * 实现Redis的SISMEMBER命令，判断Set是否包含指定成员。
 *
 * @author ljj
 */
public class SIsMember implements Command {

	private BytesWrapper key;
	private BytesWrapper member;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.SISMEMBER;
	}

	/**
	 * 解析SISMEMBER命令参数。
	 *
	 * @param array 命令数组，格式为SISMEMBER key member
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 3) {
			throw new IllegalArgumentException("SISMEMBER命令需要key和member两个参数");
		}

		if (!(array[1] instanceof BulkString keyBulkString) || !(array[2] instanceof BulkString memberBulkString)) {
			throw new IllegalArgumentException("SISMEMBER的key和member必须是BulkString");
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		BytesWrapper parsedMember = memberBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null || parsedMember == null || parsedMember.getByteArray() == null) {
			throw new IllegalArgumentException("SISMEMBER的key和member不能是NIL");
		}

		this.key = parsedKey;
		this.member = parsedMember;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		boolean exists = redisCore.containsSetMember(key, member);
		return new RespInt(exists ? 1L : 0L);
	}
}
