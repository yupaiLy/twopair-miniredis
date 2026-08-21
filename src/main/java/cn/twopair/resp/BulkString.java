package cn.twopair.resp;

import cn.twopair.datatype.BytesWrapper;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RESP块字符串（Bulk String）
 *
 * @author ljj
 */
@Getter
@AllArgsConstructor
public class BulkString implements Resp {
	/**
	 * 空值块字符串，内容为 {@code null}，对应RESP编码 {@code $-1\r\n}
	 */
	public static final BulkString NIL = new BulkString(null);
	/**
	 * 块字符串内容
	 */
	private final BytesWrapper bytesWrapper;

}
