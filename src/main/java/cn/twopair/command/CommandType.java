package cn.twopair.command;

/**
 * Redis命令类型
 *
 * @author ljj
 */
public enum CommandType {
	/** PING连接探测命令。 */
	PING,
	/** SELECT数据库选择命令。 */
	SELECT,
	/** SCAN键空间扫描命令。 */
	SCAN,
	/** TYPE数据类型查询命令。 */
	TYPE,
	/** DEL键删除命令。 */
	DEL,
	/** SET字符串写入命令。 */
	SET,
	/** SETEX带过期时间的字符串写入命令。 */
	SETEX,
	/** GET字符串读取命令。 */
	GET,
	/** EXPIRE相对过期时间命令。 */
	EXPIRE,
	/** PEXPIREAT绝对毫秒过期时间命令。 */
	PEXPIREAT,
	/** TTL剩余存活时间查询命令。 */
	TTL,
	/** LPUSH列表头部写入命令。 */
	LPUSH,
	/** LPOP列表头部弹出命令。 */
	LPOP,
	/** RPUSH列表尾部写入命令。 */
	RPUSH,
	/** RPOP列表尾部弹出命令。 */
	RPOP,
	/** LLEN列表长度查询命令。 */
	LLEN,
	/** LRANGE列表范围查询命令。 */
	LRANGE,
	/** HSET哈希字段写入命令。 */
	HSET,
	/** HMSET哈希多字段写入兼容命令。 */
	HMSET,
	/** HGET哈希字段读取命令。 */
	HGET,
	/** HDEL哈希字段删除命令。 */
	HDEL,
	/** HLEN哈希字段数量查询命令。 */
	HLEN,
	/** HSCAN哈希字段扫描命令。 */
	HSCAN,
	/** SADD集合成员添加命令。 */
	SADD,
	/** SREM集合成员删除命令。 */
	SREM,
	/** SISMEMBER集合成员判断命令。 */
	SISMEMBER,
	/** SCARD集合成员数量查询命令。 */
	SCARD,
	/** SSCAN集合成员扫描命令。 */
	SSCAN,
	/** BGREWRITEAOF后台重写AOF命令。 */
	BGREWRITEAOF

}
