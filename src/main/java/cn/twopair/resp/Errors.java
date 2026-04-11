package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ljj
 * @description RESP错误类型
 * @date 2026/4/10
 * @twopair
 */
@Getter
@AllArgsConstructor
public class Errors implements Resp {
	private final String content;
}
