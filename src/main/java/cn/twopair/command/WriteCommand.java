package cn.twopair.command;

import cn.twopair.core.RedisCore;
import cn.twopair.resp.RespArray;

import java.util.List;

/**
 * 标记修改Redis数据的命令，并定义命令转换为AOF记录的方式。
 *
 * @author ljj
 */
public interface WriteCommand extends Command {

	/**
	 * 将客户端命令转换成适合持久化的AOF命令。
	 *
	 * <p>普通写命令直接保存原始命令；带相对TTL的命令可以覆盖此方法，
	 * 转换成带绝对过期时间的命令，避免AOF重放时重新计算TTL。
	 *
	 * @param originalCommand 客户端发送的原始命令
	 * @param redisCore       命令执行后的Redis核心存储
	 * @return 需要顺序写入AOF的命令列表
	 */
	default List<RespArray> toAofCommands(
			RespArray originalCommand,
			RedisCore redisCore
	) {
		return List.of(originalCommand);
	}
}
