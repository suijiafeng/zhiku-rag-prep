package dev.zhiku.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class TextChunkerTest {
  final TextChunker chunker = new TextChunker(120, 20, 1000);

  static String doc() {
    var sb = new StringBuilder("# 第一章 总则\n\n本手册适用于全体员工。");
    for (int i = 0; i < 12; i++) sb.append("第").append(i).append("条规定内容较长，需要被切分成多个块。");
    sb.append("\n\n## 1.2 考勤\n\n员工应于工作日九点前到岗。迟到按附表处理。\r\n\r\n结尾段落。");
    return sb.toString();
  }

  @Test
  void spansPointBackIntoTheNormalizedText() {
    var normalized = TextChunker.normalize(doc());
    for (var c : chunker.chunk(doc(), true)) {
      assertThat(normalized.substring(c.start(), c.end())).isEqualTo(c.text());
      assertThat(c.text()).isEqualTo(c.text().strip());
      assertThat(c.text().length()).isLessThanOrEqualTo(120);
    }
  }

  @Test
  void everyNonWhitespaceCharacterIsCovered() {
    var normalized = TextChunker.normalize(doc());
    var covered = new BitSet();
    chunker.chunk(doc(), true).forEach(c -> covered.set(c.start(), c.end()));
    for (int i = 0; i < normalized.length(); i++) {
      if (!Character.isWhitespace(normalized.charAt(i))) assertThat(covered.get(i)).as("offset %d", i).isTrue();
    }
  }

  @Test
  void consecutiveChunksOverlapAndAreSequential() {
    var chunks = chunker.chunk(doc(), true);
    assertThat(chunks.size()).isGreaterThan(3);
    for (int i = 1; i < chunks.size(); i++) {
      assertThat(chunks.get(i).seq()).isEqualTo(i);
      assertThat(chunks.get(i).start()).isLessThan(chunks.get(i - 1).end() + 1);
      assertThat(chunks.get(i).start()).isGreaterThan(chunks.get(i - 1).start());
    }
  }

  @Test
  void markdownChunksCarryTheirHeading() {
    var chunks = chunker.chunk(doc(), true);
    assertThat(chunks.getFirst().heading()).isEqualTo("第一章 总则");
    assertThat(chunks.getLast().heading()).isEqualTo("1.2 考勤");
    assertThat(new TextChunker(120, 20, 1000).chunk(doc(), false).getFirst().heading()).isNull();
  }

  @Test
  void normalizesLineEndings() {
    assertThat(TextChunker.normalize("a\r\nb\rc")).isEqualTo("a\nb\nc");
  }

  @Test
  void rejectsDocumentsWithTooManyChunks() {
    assertThatThrownBy(() -> new TextChunker(10, 2, 3).chunk("x".repeat(100), false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidSettings() {
    assertThatThrownBy(() -> new TextChunker(10, 10, 5)).isInstanceOf(IllegalArgumentException.class);
  }
}
