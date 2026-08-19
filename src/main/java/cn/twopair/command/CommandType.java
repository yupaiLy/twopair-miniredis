package cn.twopair.command;

/**
 * @author ljj
 * @description Redis命令类型
 * @date 2026/7/10
 * @twopair
 */
public enum CommandType {
	PING,
	SELECT,
	SCAN,
	TYPE,
	DEL,
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
	HSET,
	HGET,
	HDEL,
	HLEN,
	HSCAN,
	SADD,
	SREM,
	SISMEMBER,
	SCARD,
	SSCAN,

}
