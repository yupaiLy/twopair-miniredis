package cn.twopair.core;

/**
 * @author ljj
 * @description Redis命令操作了不匹配的数据类型时抛出的领域异常。
 * @date 2026/8/18
 * @twopair
 */
public class WrongTypeException extends RuntimeException {

	public WrongTypeException() {
		super("WRONGTYPE Operation against a key holding the wrong kind of value");
	}
}