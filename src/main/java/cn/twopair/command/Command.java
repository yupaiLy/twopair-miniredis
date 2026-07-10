package cn.twopair.command;

import cn.twopair.core.RedisCore;
import cn.twopair.resp.Resp;

/**
 * @author ljj
 * @description Redis命令顶层抽象
 * @date 2026/7/10
 * @twopair
 */
public interface Command {
	/**
	 * 获取命令类型
	 *
	 * @return 命令类型
	 */
	CommandType type();

	/**
	 * 注入RESP数组参数
	 *
	 * @param array RESP数组内容
	 */
	void setContent(Resp[] array);

	/**
	 * 执行命令并返回RESP响应
	 *
	 * @param redisCore Redis核心存储
	 * @return RESP响应
	 */
	Resp handle(RedisCore redisCore);
}
