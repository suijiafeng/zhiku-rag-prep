package dev.zhiku.chat;

import java.util.regex.Pattern;

/** Metadata-level citation check: markers exist and map to provided sources. Not a semantic support check. */
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
