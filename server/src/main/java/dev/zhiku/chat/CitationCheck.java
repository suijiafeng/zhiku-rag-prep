package dev.zhiku.chat;

import java.util.regex.Pattern;

/** 元数据级引用检查：标记存在且对应到提供的来源。不是语义支撑检查。 */
final class CitationCheck {
  enum Result { OK, NONE, OUT_OF_RANGE }

  private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,9})\\]");
  private static final Pattern TOO_LONG = Pattern.compile("\\[\\d{10,}\\]");

  private CitationCheck() {}

  static Result check(String answer, int sources) {
    if (TOO_LONG.matcher(answer).find()) return Result.OUT_OF_RANGE;
    var m = MARKER.matcher(answer);
    boolean any = false;
    while (m.find()) {
      any = true;
      int n = Integer.parseInt(m.group(1));
      if (n < 1 || n > sources) return Result.OUT_OF_RANGE;
    }
    return any ? Result.OK : Result.NONE;
  }
}
