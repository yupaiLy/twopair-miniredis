package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RESP简单字符串
 *
 * @author ljj
 */
@Getter
@AllArgsConstructor
public class SimpleString implements Resp {
	/**
	 * 简单字符串内容
	 */
	private final String content;
}
