package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RESP错误类型
 *
 * @author ljj
 */
@Getter
@AllArgsConstructor
public class Errors implements Resp {
	/**
	 * 错误信息内容
	 */
	private final String content;
}
