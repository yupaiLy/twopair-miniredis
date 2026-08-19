package cn.twopair.core;

import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;

import java.util.List;

/**
 * @author ljj
 * @description Redis核心接口
 * @date 2026/4/9
 * @twopair
 */
public interface RedisCore {
	void put(BytesWrapper key, RedisData value);

	RedisData get(BytesWrapper key);

	boolean exist(BytesWrapper key);

	/**
	 * @author ljj
	 * @description 为指定 key 设置以秒为单位的过期时间。
	 * @date 2026/7/16
	 * @twopair
	 */
	boolean expire(BytesWrapper key, long seconds);

	/**
	 * 为指定key设置绝对毫秒过期时间。
	 *
	 * @param key            需要设置过期时间的key
	 * @param expireAtMillis 绝对毫秒时间戳
	 * @return key存在并成功处理时返回true
	 */
	boolean expireAt(BytesWrapper key, long expireAtMillis);

	/**
	 * @author ljj
	 * @description 查询指定 key 剩余的存活秒数。
	 * @date 2026/7/16
	 * @twopair
	 */
	long ttl(BytesWrapper key);

	/**
	 * @author ljj
	 * @description 一次写入数据及其以秒为单位的过期时间。
	 * @date 2026/8/11
	 * @twopair
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
	 * @return 弹出的元素；key不存在时返回null
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



}
