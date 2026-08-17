package cn.twopair.resp;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author ljj
 * @description Resp整数对象
 * @date 2026/4/10
 * @twopair
 */
@Getter
@AllArgsConstructor
public class RespInt implements Resp {
	// RESP 整数是有符号 64 位整数，使用 long。
	private final long value;
}
