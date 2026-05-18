package cn.twopair.datatype;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * @author ljj
 * @date 2026/4/9
 * @twopair
 */
public class BytesWrapper implements Comparable<BytesWrapper> {
	public static final Charset CHARSET = StandardCharsets.UTF_8;
	private final byte[] content;

	public BytesWrapper(byte[] content) {
		this.content = content;
	}


	/**
	 * 获取字节数组内容
	 *
	 * @return 字节数组
	 */
	public byte[] getByteArray() {
		return content;
	}


	/**
	 * 将字节数组转换为UTF-8编码的字符串
	 *
	 * @return UTF-8编码的字符串表示
	 */
	public String toUtf8String() {
		return new String(content, CHARSET);
	}


	/**
	 * 判断两个字节数组wrapper对象内容是否相等
	 *
	 * @param o 比较对象
	 * @return 是否相等
	 */
	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()) return false;
		if (this == o) return true;

		BytesWrapper that = (BytesWrapper) o;

		return Arrays.equals(content, that.content);
	}

	/**
	 * 获取字节数组wrapper对象的哈希码
	 *
	 * @return 哈希码
	 */
	@Override
	public int hashCode() {
		return Arrays.hashCode(content);
	}

	/**
	 * 比较两个字节数组wrapper对象
	 *
	 * @param o 比较对象
	 * @return 比较结果
	 */
	@Override
	public int compareTo(BytesWrapper o) {
		int lenThis = content.length;
		int lenThat = o.content.length;

		for (int i = 0; i < lenThis && i < lenThat; ++i) {
			int a = content[i] & 0xff;
			int b = o.content[i] & 0xff;

			if (a != b) return a - b;
		}
		return lenThis - lenThat;
	}
}
