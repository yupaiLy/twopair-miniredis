package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ljj
 * @description RESP简单字符串
 * @date 2026/4/10
 * @twopair
 */
@Getter
@AllArgsConstructor
public class SimpleString implements Resp {
	private final String content;
}
