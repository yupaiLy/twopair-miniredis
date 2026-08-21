package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RESP整数对象
 *
 * @author ljj
 */
@Getter
@AllArgsConstructor
public class RespInt implements Resp {
	/**
	 * RESP整数是有符号64位整数，因此使用 {@code long} 保存。
	 */
	private final long value;
}
