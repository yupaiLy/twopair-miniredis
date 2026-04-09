package cn.twopair.datatype;

/**
 * @author ljj
 * @description Redis数据对象接口
 * @date 2026/4/9
 * @twopair
 */
public interface RedisData {
	/**
	 * 获取数据对象超时时间
	 *
	 * @return 超时时间
	 */
	long timeout();

	/**
	 * 设置数据对象超时时间
	 *
	 * @param timeout 超时时间
	 */
	void setTimeout(long timeout);
}
