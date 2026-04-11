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
	private final long value;
}
