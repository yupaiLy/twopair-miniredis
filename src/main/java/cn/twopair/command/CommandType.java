package cn.twopair.command;

/**
 * @author ljj
 * @description Redis命令类型
 * @date 2026/7/10
 * @twopair
 */
public enum CommandType {
	PING,
	SET,
	SETEX,
	GET,
	EXPIRE,
	PEXPIREAT,
	TTL,
	LPUSH,
	LPOP,
	RPUSH,
	RPOP,
	LLEN,
	LRANGE,

}
