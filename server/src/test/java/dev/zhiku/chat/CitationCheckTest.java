package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitationCheckTest {
  @Test
  void acceptsMarkersWithinTheSourceRange() {
    assertThat(CitationCheck.check("甲[1]乙[2][1]", 3)).isEqualTo(CitationCheck.Result.OK);
  }

  @Test
  void rejectsAnswersWithoutMarkers() {
    assertThat(CitationCheck.check("资料中没有足够依据。", 3)).isEqualTo(CitationCheck.Result.NONE);
  }

  @Test
  void rejectsOutOfRangeMarkers() {
    assertThat(CitationCheck.check("甲[4]", 3)).isEqualTo(CitationCheck.Result.OUT_OF_RANGE);
    assertThat(CitationCheck.check("甲[0]", 3)).isEqualTo(CitationCheck.Result.OUT_OF_RANGE);
    assertThat(CitationCheck.check("甲[99999999999]", 3)).isEqualTo(CitationCheck.Result.OUT_OF_RANGE);
  }
}
