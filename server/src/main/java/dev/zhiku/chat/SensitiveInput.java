package dev.zhiku.chat;

import java.util.regex.Pattern;

/**
 * 识别问题中的个人敏感号码（身份证号、银行卡号、手机号）。只按号码格式判断，会漏判也可能误判；
 * 不按"工资"等关键词判断，以免拦下正常的制度问题。
 */
final class SensitiveInput {
  private static final Pattern NUMBERS = Pattern.compile(
      "(?<![0-9])(?:[0-9]{17}[0-9Xx]|[0-9]{16,19}|1[3-9][0-9]{9})(?![0-9Xx])");

  private SensitiveInput() {}

  static boolean detect(String text) {
    return NUMBERS.matcher(text).find();
  }

  static String redact(String text) {
    return NUMBERS.matcher(text).replaceAll("[已隐藏]");
  }
}
