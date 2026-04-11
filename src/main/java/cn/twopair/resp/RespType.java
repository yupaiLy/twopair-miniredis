package cn.twopair.resp;

/**
 * @author ljj
 * @description RESP协议类型枚举
 * @date 2026/4/10
 * @twopair
 */
public enum RespType {
	// 简单字符串类型
	STRING('+'),
	// 错误类型
	ERROR('-'),
	// 负号
	NEGATIVE('-'),
	// 整数类型
	INTEGER(':'),
	// 字符串类型
	BULK_STRING('$'),
	// 数组类型
	ARRAY('*'),
	// 完整换行符
	R('\r'),
	N('\n'),
	// 数字0
	ZERO('0'),
	// 数字1
	ONE('1'),
	// 数字9
	NINE('9');

	private final char code;

	RespType(char code) {
		this.code = code;
	}

	public char getCode() {
		return code;
	}

}
