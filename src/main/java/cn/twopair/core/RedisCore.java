package cn.twopair.core;

import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;

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
}
