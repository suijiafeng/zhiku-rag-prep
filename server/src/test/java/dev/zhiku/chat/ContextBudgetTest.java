package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.retrieval.Retrieval.Candidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
  static Candidate chunk(int chars) {
    return new Candidate(UUID.randomUUID(), UUID.randomUUID(), "手册", UUID.randomUUID(), "v1", 0, null, 0, chars, "字".repeat(chars), 0.9, 1);
  }

  @Test
  void keepsChunksInOrderUntilTheBudgetIsSpent() {
    var ranked = List.of(chunk(400), chunk(400), chunk(400), chunk(400));
    var selected = ContextBudget.select(ranked, 0, 1500, 1000);
    assertThat(selected.kept()).containsExactly(ranked.get(0), ranked.get(1));
    assertThat(selected.squeezedOut()).isEqualTo(2);
  }

  @Test
  void priorTurnsAndTruncationCountAgainstTheBudget() {
    var ranked = List.of(chunk(5000), chunk(100), chunk(100));
    var selected = ContextBudget.select(ranked, 300, 1500, 1900);
    assertThat(selected.kept()).containsExactly(ranked.get(0), ranked.get(1));
    assertThat(selected.squeezedOut()).isEqualTo(1);
  }

  @Test
  void theFirstChunkIsAlwaysKept() {
    var ranked = List.of(chunk(900), chunk(10));
    var selected = ContextBudget.select(ranked, 500, 1500, 1000);
    assertThat(selected.kept()).containsExactly(ranked.get(0));
    assertThat(selected.squeezedOut()).isEqualTo(1);
  }
}
