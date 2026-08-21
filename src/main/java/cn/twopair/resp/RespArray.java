package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RESP数组
 *
 * @author ljj
 */
@Getter
@AllArgsConstructor
public class RespArray implements Resp {
	/**
	 * RESP数组元素
	 */
	private final Resp[] array;
	/**
	 * 空值数组，内容为 {@code null}，对应RESP编码 {@code *-1\r\n}
	 *
	 * @see <a href="https://redis.com.cn/topics/protocol.html">RESP协议说明</a>
	 */
	public static RespArray NIL = new RespArray(null);
}
