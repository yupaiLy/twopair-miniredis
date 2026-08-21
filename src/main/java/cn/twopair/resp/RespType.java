package cn.twopair.resp;

import lombok.Getter;

/**
 * RESP协议类型枚举
 *
 * @author ljj
 */
@Getter
public enum RespType {
	/** 简单字符串类型，前缀为 {@code +}。 */
	STATUS('+'),
	/** 错误类型，前缀为 {@code -}。 */
	ERROR('-'),
	/** 数字负号字符 {@code -}。 */
	NEGATIVE('-'),
	/** 整数类型，前缀为 {@code :}。 */
	INTEGER(':'),
	/** 块字符串类型，前缀为 {@code $}。 */
	BULK_STRING('$'),
	/** 数组类型，前缀为 {@code *}。 */
	ARRAY('*'),
	/** 回车符CR。 */
	R('\r'),
	/** 换行符LF。 */
	N('\n'),
	/** 数字字符0，编码空块字符串长度时使用。 */
	ZERO('0'),
	/** 数字字符1。 */
	ONE('1'),
	/** 数字字符9。 */
	NINE('9');

	private final char code;

	/**
	 * 创建RESP协议类型或辅助字符枚举项。
	 *
	 * @param code 对应的协议字符
	 */
	RespType(char code) {
		this.code = code;
	}

}
