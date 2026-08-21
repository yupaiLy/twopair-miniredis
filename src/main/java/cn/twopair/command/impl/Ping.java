package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * PING命令
 *
 * @author ljj
 */
public class Ping implements Command {
	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.PING;
	}

	/**
	 * PING不携带参数，无需解析。
	 *
	 * @param array RESP数组内容，当前版本忽略
	 */
	@Override
	public void setContent(Resp[] array) {

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		return new SimpleString("PONG");
	}
}
