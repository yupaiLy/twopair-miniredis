package cn.twopair.datatype;

import lombok.Getter;
import lombok.Setter;

/**
 * @author ljj
 * @description Redis字符串对象
 * @date 2026/4/9
 * @twopair
 */
public class RedisString implements RedisData {
	/**
	 * 过期时间(绝对时间戳)
	 */
	private volatile long timeout = -1;

	@Getter
	@Setter
	private BytesWrapper value;

	public RedisString(BytesWrapper value) {
		this.value = value;
	}

	public RedisString() {
	}

	@Override
	public long timeout() {
		return timeout;
	}

	@Override
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}
}
