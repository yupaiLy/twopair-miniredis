package cn.twopair.command.impl;

import cn.twopair.command.AofManagementCommand;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.persistence.aof.AofPersistence;
import cn.twopair.resp.Errors;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * 在后台重写AOF文件，使用当前内存状态生成最小恢复命令。
 *
 * @author ljj
 */
public class BgRewriteAof implements AofManagementCommand {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.BGREWRITEAOF;
	}

	/**
	 * 校验BGREWRITEAOF不携带额外参数。
	 *
	 * @param array RESP命令数组
	 * @throws IllegalArgumentException 参数数量不正确时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 1) {
			throw new IllegalArgumentException("BGREWRITEAOF命令不需要参数");
		}
	}

	/**
	 * 启动后台AOF Rewrite任务。
	 *
	 * @param redisCore Redis内存数据库
	 * @param aofPersistence AOF持久化协调器；未启用AOF时为 {@code null}
	 * @return Rewrite启动结果
	 */
	@Override
	public Resp handle(RedisCore redisCore, AofPersistence aofPersistence) {
		if (aofPersistence == null) {
			return new Errors("ERR AOF未启用");
		}

		if (!aofPersistence.rewriteAsync(redisCore)) {
			return new Errors("ERR Background append only file rewriting already in progress");
		}

		return new SimpleString("Background append only file rewriting started");
	}
}