package cn.twopair.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * @author ljj
 * @description 测试命令traceId生成器的非空和唯一性。
 * @date 2026/8/19
 * @twopair
 */
public class TraceIdGeneratorTest {

	/**
	 * 验证连续生成的traceId均不为空且不会重复。
	 */
	@Test
	public void testGenerateUniqueTraceIds() {
		Set<String> traceIds = new HashSet<>();

		for (int i = 0; i < 1000; i++) {
			String traceId = TraceIdGenerator.next();
			Assert.assertFalse(traceId.isBlank());
			Assert.assertTrue(traceIds.add(traceId));
		}
	}
}
