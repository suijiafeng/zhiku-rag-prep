package dev.zhiku.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 带重叠的定长字符切块，优先在段落、行和句子边界处切分。
 * 偏移量基于 {@link #normalize(String)} 的输出，以便评测标注能将来源片段映射到 chunk。
 */
public final class TextChunker {
  public record Chunk(int seq, int start, int end, String heading, String text) {}

  private static final Pattern HEADING = Pattern.compile("^#{1,6}[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
  private static final String SENTENCE_ENDS = "。！？；.!?;";

  private final int maxChars;
  private final int overlap;
  private final int maxChunks;

  public TextChunker(int maxChars, int overlap, int maxChunks) {
    if (maxChars <= 0 || overlap < 0 || overlap >= maxChars || maxChunks <= 0) throw new IllegalArgumentException("Invalid chunk settings");
    this.maxChars = maxChars;
    this.overlap = overlap;
    this.maxChunks = maxChunks;
  }

  public static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  public List<Chunk> chunk(String raw, boolean markdown) {
    var text = normalize(raw);
    var headings = markdown ? headings(text) : new TreeMap<Integer, String>();
    var chunks = new ArrayList<Chunk>();
    int start = skipWhitespace(text, 0);
    while (start < text.length()) {
      int hardEnd = Math.min(start + maxChars, text.length());
      int end = hardEnd < text.length() ? breakPoint(text, start, hardEnd) : hardEnd;
      int trimmed = end;
      while (trimmed > start && Character.isWhitespace(text.charAt(trimmed - 1))) trimmed--;
      if (trimmed > start) {
        if (chunks.size() >= maxChunks) throw new IllegalArgumentException("Document exceeds the chunk limit");
        var heading = headings.floorEntry(start);
        chunks.add(new Chunk(chunks.size(), start, trimmed, heading == null ? null : heading.getValue(), text.substring(start, trimmed)));
      }
      if (end >= text.length()) break;
      int next = skipWhitespace(text, Math.max(end - overlap, start + 1));
      if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) next++;
      start = next > start ? next : end;
    }
    return chunks;
  }

  private int breakPoint(String text, int start, int hardEnd) {
    int floor = start + maxChars / 2;
    int paragraph = text.lastIndexOf("\n\n", hardEnd - 1);
    if (paragraph >= floor) return paragraph;
    int line = text.lastIndexOf('\n', hardEnd - 1);
    if (line >= floor) return line;
    for (int i = hardEnd - 1; i >= floor; i--) {
      if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) return i + 1;
    }
    return Character.isHighSurrogate(text.charAt(hardEnd - 1)) ? hardEnd - 1 : hardEnd;
  }

  private static int skipWhitespace(String text, int from) {
    int i = from;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
    return i;
  }

  private static TreeMap<Integer, String> headings(String text) {
    var map = new TreeMap<Integer, String>();
    int lineStart = 0;
    for (var line : text.split("\n", -1)) {
      var m = HEADING.matcher(line);
      if (m.matches()) map.put(lineStart, m.group(1));
      lineStart += line.length() + 1;
    }
    return map;
  }
}
