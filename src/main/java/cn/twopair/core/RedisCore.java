package cn.twopair.core;

import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;

import java.util.List;
import java.util.Map;

/**
 * Redis核心接口
 *
 * @author ljj
 */
public interface RedisCore {
	/**
	 * 写入键值对，覆盖已有数据且不设置过期时间。
	 *
	 * @param key   键
	 * @param value 需要写入的值
	 */
	void put(BytesWrapper key, RedisData value);

	/**
	 * 读取指定key的数据，读取时顺带完成惰性过期删除。
	 *
	 * @param key 键
	 * @return 对应的数据；key不存在或已过期时返回 {@code null}
	 */
	RedisData get(BytesWrapper key);

	/**
	 * 判断key是否存在，已过期的数据在逻辑上等同于不存在。
	 *
	 * @param key 键
	 * @return key存在时返回 {@code true}
	 */
	boolean exist(BytesWrapper key);

	/**
	 * 原子地删除一个或多个key，已过期的数据在逻辑上等同于不存在。
	 *
	 * @param keys 需要删除的key
	 * @return 实际删除的key数量
	 * @throws NullPointerException     当 {@code keys} 或其中某个key为 {@code null} 时抛出
	 * @throws IllegalArgumentException 当 {@code keys} 为空列表时抛出
	 */
	long delete(List<BytesWrapper> keys);

	/**
	 * 为指定key设置以秒为单位的过期时间，非正数表示立即删除。
	 *
	 * @param key     需要设置过期时间的key
	 * @param seconds 过期秒数
	 * @return key存在并成功处理时返回 {@code true}
	 */
	boolean expire(BytesWrapper key, long seconds);

	/**
	 * 为指定key设置绝对毫秒过期时间。
	 *
	 * @param key            需要设置过期时间的key
	 * @param expireAtMillis 绝对毫秒时间戳
	 * @return key存在并成功处理时返回 {@code true}
	 */
	boolean expireAt(BytesWrapper key, long expireAtMillis);

	/**
	 * 查询指定key剩余的存活秒数。
	 *
	 * @param key 键
	 * @return 剩余秒数；key不存在时返回 {@code -2}，key永久有效时返回 {@code -1}
	 */
	long ttl(BytesWrapper key);

	/**
	 * 一次写入数据及其以秒为单位的过期时间。
	 *
	 * @param key     需要写入的键，不能为 {@code null}
	 * @param value   需要写入的值，不能为 {@code null}
	 * @param seconds 过期秒数，必须大于0
	 * @throws IllegalArgumentException 当 {@code seconds} 小于等于0时抛出
	 */
	void putWithExpiration(BytesWrapper key, RedisData value, long seconds);

	/**
	 * 主动扫描并删除已经过期的数据。
	 *
	 * @return 本次成功删除的数据数量
	 */
	int removeExpired();

	/**
	 * 原子地向列表头部压入多个元素，不存在时自动创建列表。
	 *
	 * @param key      列表key
	 * @param elements 需要压入的元素
	 * @return 压入完成后的列表长度
	 * @throws WrongTypeException key存在但不是列表时抛出
	 */
	long leftPush(BytesWrapper key, List<BytesWrapper> elements);

	/**
	 * 原子地从列表头部弹出一个元素，列表为空后删除key。
	 *
	 * @param key 列表key
	 * @return 弹出的元素；key不存在时返回 {@code null}
	 * @throws WrongTypeException key存在但不是列表时抛出
	 */
	BytesWrapper leftPop(BytesWrapper key);

	/**
	 * 原子地获取列表长度。
	 *
	 * @param key 列表key
	 * @return 列表长度；key不存在时返回0
	 * @throws WrongTypeException key存在但不是列表时抛出
	 */
	long listLength(BytesWrapper key);

	/**
	 * 原子地查询列表指定范围内的元素。
	 *
	 * @param key   列表key
	 * @param start 起始下标
	 * @param stop  结束下标
	 * @return 范围内的元素；key不存在时返回空列表
	 * @throws WrongTypeException key存在但不是列表时抛出
	 */
	List<BytesWrapper> listRange(BytesWrapper key, long start, long stop);

	/**
	 * 原子地向Hash写入一个或多个字段，不存在时自动创建Hash。
	 *
	 * @param key    Hash的key
	 * @param fields 需要写入的字段和值
	 * @return 本次新增的字段数量，覆盖已有字段不计数
	 * @throws WrongTypeException key存在但不是Hash时抛出
	 */
	long putHashFields(BytesWrapper key, Map<BytesWrapper, BytesWrapper> fields);

	/**
	 * 原子地读取Hash中的指定字段。
	 *
	 * @param key   Hash的key
	 * @param field 需要读取的字段
	 * @return 字段值；key或field不存在时返回 {@code null}
	 * @throws WrongTypeException key存在但不是Hash时抛出
	 */
	BytesWrapper getHashField(BytesWrapper key, BytesWrapper field);

	/**
	 * 原子地删除Hash中的一个或多个字段。
	 *
	 * @param key    Hash的key
	 * @param fields 需要删除的字段
	 * @return 实际删除的字段数量
	 * @throws WrongTypeException key存在但不是Hash时抛出
	 */
	long deleteHashFields(BytesWrapper key, List<BytesWrapper> fields);

	/**
	 * 原子地获取Hash包含的字段数量。
	 *
	 * @param key Hash的key
	 * @return 字段数量；key不存在时返回0
	 * @throws WrongTypeException key存在但不是Hash时抛出
	 */
	long getHashSize(BytesWrapper key);

	/**
	 * 获取Hash字段和值的有序快照。
	 *
	 * @param key Hash的key
	 * @return key不存在时返回空列表
	 * @throws WrongTypeException key存在但不是Hash时抛出
	 */
	List<Map.Entry<BytesWrapper, BytesWrapper>> scanHashEntries(BytesWrapper key);

	/**
	 * 原子地向Set添加一个或多个成员，不存在时自动创建Set。
	 *
	 * @param key     Set的key
	 * @param members 需要添加的成员
	 * @return 本次新增的成员数量，已存在成员不计数
	 * @throws WrongTypeException key存在但不是Set时抛出
	 */
	long addSetMembers(BytesWrapper key, List<BytesWrapper> members);

	/**
	 * 原子地删除Set中的一个或多个成员。
	 *
	 * @param key     Set的key
	 * @param members 需要删除的成员
	 * @return 实际删除的成员数量
	 * @throws WrongTypeException key存在但不是Set时抛出
	 */
	long removeSetMembers(BytesWrapper key, List<BytesWrapper> members);

	/**
	 * 原子地判断Set是否包含指定成员。
	 *
	 * @param key    Set的key
	 * @param member 需要判断的成员
	 * @return 成员存在时返回 {@code true}，key或成员不存在时返回 {@code false}
	 * @throws WrongTypeException key存在但不是Set时抛出
	 */
	boolean containsSetMember(BytesWrapper key, BytesWrapper member);

	/**
	 * 原子地获取Set包含的唯一成员数量。
	 *
	 * @param key Set的key
	 * @return 成员数量；key不存在时返回0
	 * @throws WrongTypeException key存在但不是Set时抛出
	 */
	long getSetSize(BytesWrapper key);

	/**
	 * 获取Set成员的有序快照。
	 *
	 * @param key Set的key
	 * @return key不存在时返回空列表
	 * @throws WrongTypeException key存在但不是Set时抛出
	 */
	List<BytesWrapper> scanSetMembers(BytesWrapper key);

	/**
	 * 获取当前所有未过期key的弱一致性快照。
	 *
	 * @return 当前可见的key列表
	 */
	List<BytesWrapper> scanKeys();
}
