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
}
