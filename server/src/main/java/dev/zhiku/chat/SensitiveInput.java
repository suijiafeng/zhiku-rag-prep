package dev.zhiku.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别问题中的个人敏感号码（身份证号、银行卡号、手机号）。格式匹配后再验校验位：
 * 身份证按 GB 11643 校验码，银行卡按 Luhn，单号、合同号这类长数字串不再被误拦。
 * 仍可能漏判（无校验位的号码、带分隔符的写法）；不按"工资"等关键词判断，以免拦下正常的制度问题。
 */
final class SensitiveInput {
  private static final Pattern NUMBERS = Pattern.compile(
      "(?<![0-9])(?:(?<id>[0-9]{17}[0-9Xx])|(?<card>[0-9]{16,19})|(?<phone>1[3-9][0-9]{9}))(?![0-9Xx])");
  private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
  private static final char[] ID_CHECK = "10X98765432".toCharArray();

  private SensitiveInput() {}

  /** 返回识别到的第一类号码的名称；没有则为空。 */
  static Optional<String> detect(String text) {
    var m = NUMBERS.matcher(text);
    while (m.find()) {
      var kind = kind(m);
      if (kind != null) return Optional.of(kind);
    }
    return Optional.empty();
  }

  static String redact(String text) {
    var m = NUMBERS.matcher(text);
    var sb = new StringBuilder();
    while (m.find()) m.appendReplacement(sb, kind(m) == null ? Matcher.quoteReplacement(m.group()) : "[已隐藏]");
    return m.appendTail(sb).toString();
  }

  private static String kind(Matcher m) {
    if (m.group("phone") != null) return "手机号";
    if (m.group("id") != null && idChecksumValid(m.group("id"))) return "证件号";
    if (m.group("card") != null && luhnValid(m.group("card"))) return "银行卡号";
    return null;
  }

  static boolean idChecksumValid(String id) {
    int sum = 0;
    for (int i = 0; i < 17; i++) sum += (id.charAt(i) - '0') * ID_WEIGHTS[i];
    return Character.toUpperCase(id.charAt(17)) == ID_CHECK[sum % 11];
  }

  static boolean luhnValid(String digits) {
    int sum = 0;
    boolean twice = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (twice) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      twice = !twice;
    }
    return sum % 10 == 0;
  }
}
