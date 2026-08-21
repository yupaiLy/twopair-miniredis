package cn.twopair.command;

import cn.twopair.core.RedisCore;
import cn.twopair.persistence.aof.AofPersistence;
import cn.twopair.resp.Resp;

/**
 * 需要访问AOF持久化协调器的服务管理命令。
 *
 * @author ljj
 */
public interface AofManagementCommand extends Command {

	/**
	 * 使用{@link RedisCore Redis核心存储}和{@link AofPersistence AOF持久化协调器}执行命令。
	 *
	 * @param redisCore {@link RedisCore Redis核心存储}
	 * @param aofPersistence {@link AofPersistence AOF持久化协调器}；未启用AOF时为 {@code null}
	 * @return {@link Resp RESP命令响应}
	 */
	Resp handle(RedisCore redisCore, AofPersistence aofPersistence);

	/**
	 * {@inheritDoc}
	 */
	@Override
	default Resp handle(RedisCore redisCore) {
		return handle(redisCore, null);
	}
}
