package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * @author ljj
 * @description PING命令
 * @date 2026/7/10
 * @twopair
 */
public class Ping implements Command {
	@Override
	public CommandType type() {
		return CommandType.PING;
	}

	@Override
	public void setContent(Resp[] array) {

	}

	@Override
	public Resp handle(RedisCore redisCore) {
		return new SimpleString("PONG");
	}
}
