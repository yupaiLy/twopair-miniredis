package cn.twopair.resp;

/**
 * @author ljj
 * @description RESP数组
 * @date 2026/4/10
 * @twopair
 */

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RespArray implements Resp {
	private final Resp[] array;
	/**
	 * 空值数组(null) 对应*-1\r\n
	 * <a>https://redis.com.cn/topics/protocol.html</a>
	 */
	public static RespArray NIL = new RespArray(null);
}
