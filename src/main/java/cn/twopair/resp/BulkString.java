package cn.twopair.resp;

import cn.twopair.datatype.BytesWrapper;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ljj
 * @description RESP字符串
 * @date 2026/4/10
 * @twopair
 */
@Getter
@AllArgsConstructor
public class BulkString implements Resp {
	/**
	 * 空值 块字符串，对应$-1\r\n\r\n
	 */
	public static final BulkString NIL = new BulkString(null);
	/**
	 * 内容
	 */
	private final BytesWrapper bytesWrapper;

}
