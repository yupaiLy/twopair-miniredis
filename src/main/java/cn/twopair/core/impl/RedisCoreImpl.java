package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ljj
 * @description Redis核心实现类
 * @date 2026/4/9
 * @twopair
 */
public class RedisCoreImpl implements RedisCore {
	private final ConcurrentHashMap<BytesWrapper, RedisData> map = new ConcurrentHashMap<>();

	@Override
	public void put(BytesWrapper key, RedisData value) {
		map.put(key, value);
	}

	/**
	 * 获取数据同时检测数据是否过期（惰性删除）
	 *
	 * @param key 键
	 * @return 值
	 */
	@Override
	public RedisData get(BytesWrapper key) {
		RedisData redisData = map.get(key);
		if (redisData == null) {
			return null;
		}
		if (redisData.timeout() == -1) {
			return redisData;
		}
		if (redisData.timeout() < System.currentTimeMillis()) {
			map.remove(key);
			return null;
		}
		return redisData;
	}

	@Override
	public boolean exist(BytesWrapper key) {
		// 直接调用获取方法
		return get(key) != null;
	}
}
