package cn.twopair.core;

/**
 * Redis命令操作了不匹配的数据类型时抛出的领域异常。
 *
 * @author ljj
 */
public class WrongTypeException extends RuntimeException {

	/**
	 * 使用Redis标准WRONGTYPE错误信息创建异常。
	 */
	public WrongTypeException() {
		super("WRONGTYPE Operation against a key holding the wrong kind of value");
	}
}
