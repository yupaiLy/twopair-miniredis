package cn.twopair.resp;

/**
 * 表示 RESP 数据尚未接收完整。
 *
 * @author ljj
 */
final class RespIncompleteException extends IllegalStateException {
	/**
	 * 使用固定消息，便于调试时区分半包和非法协议。
	 */
	RespIncompleteException() {
		super("RESP数据不完整");
	}
}
