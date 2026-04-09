package cn.twopair.datatype;

/**
 * @author ljj
 * @date 2026/4/9
 * @twopair
 */
public class BytesWrapperTests {
	public static void main(String[] args) {
		BytesWrapper a = new BytesWrapper("twopair".getBytes());
		BytesWrapper b = new BytesWrapper("twopair".getBytes());
		System.out.println(a.equals(b)); // true
		System.out.println(a.hashCode() == b.hashCode()); // true
	}
}
