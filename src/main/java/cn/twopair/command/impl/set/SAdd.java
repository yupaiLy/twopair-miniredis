package cn.twopair.command.impl.set;

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
 * @description 实现Redis的SADD命令，向Set添加一个或多个成员。
 * @date 2026/8/19
 * @twopair
 */
public class SAdd implements WriteCommand {

	private BytesWrapper key;
	private List<BytesWrapper> members;

	@Override
	public CommandType type() {
		return CommandType.SADD;
	}

	/**
	 * 解析SADD命令参数。
	 *
	 * @param array 命令数组，格式为SADD key member [member ...]
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 3) {
			throw new IllegalArgumentException("SADD命令需要key和至少一个member参数");
		}

		// SADD的key和全部member都必须使用BulkString传输。
		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString)) {
				throw new IllegalArgumentException("SADD的key和member必须是BulkString");
			}
		}

		BytesWrapper parsedKey = ((BulkString) array[1]).getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("SADD的key不能是NIL");
		}

		List<BytesWrapper> parsedMembers = new ArrayList<>(array.length - 2);

		for (int i = 2; i < array.length; i++) {
			BytesWrapper member = ((BulkString) array[i]).getBytesWrapper();

			if (member == null || member.getByteArray() == null) {
				throw new IllegalArgumentException("SADD的member不能是NIL");
			}

			// 保留重复member，由RedisSet负责去重并统计真正新增的数量。
			parsedMembers.add(member);
		}

		// 全部参数验证成功后再修改命令对象。
		this.key = parsedKey;
		this.members = parsedMembers;
	}

	@Override
	public Resp handle(RedisCore redisCore) {
		return new RespInt(redisCore.addSetMembers(key, members));
	}
}