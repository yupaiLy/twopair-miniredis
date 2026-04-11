package cn.twopair.resp;

/**
 * @author ljj
 * @description RESP数组
 * @date 2026/4/10
 * @twopair
 */

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RespArray implements Resp {
	private final Resp[] array;
}
