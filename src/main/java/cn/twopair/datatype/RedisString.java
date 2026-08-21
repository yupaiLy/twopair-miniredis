package cn.twopair.datatype;

import lombok.Getter;
import lombok.Setter;

/**
 * Redis字符串对象
 *
 * @author ljj
 */
public class RedisString implements RedisData {
	/**
	 * 绝对毫秒过期时间，-1表示永久有效。
	 */
	private volatile long timeout = -1;

	/**
	 * 字符串内容
	 */
	@Getter
	@Setter
	private BytesWrapper value;

	/**
	 * 使用指定内容创建Redis字符串对象。
	 *
	 * @param value 字符串的二进制安全内容
	 */
	public RedisString(BytesWrapper value) {
		this.value = value;
	}

	/**
	 * 创建尚未设置内容的Redis字符串对象。
	 */
	public RedisString() {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long timeout() {
		return timeout;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}
}
