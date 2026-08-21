package cn.twopair.datatype;

/**
 * Redis数据对象接口
 *
 * @author ljj
 */
public interface RedisData {
	/**
	 * 获取数据的绝对毫秒过期时间。
	 *
	 * @return 绝对毫秒时间戳；-1表示永久有效
	 */
	long timeout();

	/**
	 * 设置数据的绝对毫秒过期时间。
	 *
	 * @param timeout 绝对毫秒时间戳；-1表示永久有效
	 */
	void setTimeout(long timeout);
}
