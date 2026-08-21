package cn.twopair.datatype;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 字节数组包装对象，基于内容提供相等性和比较语义。
 *
 * @author ljj
 */
public class BytesWrapper implements Comparable<BytesWrapper> {
	/** 项目统一使用的UTF-8字符集。 */
	public static final Charset CHARSET = StandardCharsets.UTF_8;
	private final byte[] content;

	/**
	 * 创建字节数组包装对象。
	 *
	 * @param content 需要包装的字节数组；允许为 {@code null}，但内容访问和比较方法要求数组非空
	 */
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
	 * 判断两个字节包装对象的内容是否相等。
	 *
	 * @param o 比较对象
	 * @return 内容相等时返回 {@code true}
	 */
	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass()) return false;
		if (this == o) return true;

		BytesWrapper that = (BytesWrapper) o;

		return Arrays.equals(content, that.content);
	}

	/**
	 * 获取字节包装对象的哈希码。
	 *
	 * @return 基于内容的哈希码
	 */
	@Override
	public int hashCode() {
		return Arrays.hashCode(content);
	}

	/**
	 * 按无符号字节值逐个比较两个字节包装对象。
	 *
	 * @param o 比较对象
	 * @return 当前对象小于、等于或大于指定对象时分别返回负数、0或正数
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
