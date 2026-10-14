package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
  static String chars(int n) {
    return "字".repeat(n);
  }

  @Test
  void keepsChunksInOrderUntilTheBudgetIsSpent() {
    var plan = ContextBudget.plan(100, 10, List.of(), List.of(chars(400), chars(400), chars(400), chars(400)), 1000);
    assertThat(plan.keptChunks()).isEqualTo(2);
    assertThat(plan.squeezedOut()).isEqualTo(2);
    assertThat(plan.usedChars()).isEqualTo(900);
  }

  /** 评审复现：三个 8000 字的前序回答 + 一块证据曾经全部送入 16000 的预算。 */
  @Test
  void priorTurnsAreDroppedOldestFirstSoTheWholePromptStaysWithinBudget() {
    var turns = List.of(chars(8000), chars(8000), chars(8000));
    var plan = ContextBudget.plan(1500, 40, turns, List.of(chars(800), chars(800)), 16000);
    assertThat(plan.keptTurnsFrom()).isEqualTo(2);
    assertThat(plan.droppedTurns()).isEqualTo(2);
    assertThat(plan.keptChunks()).isEqualTo(2);
    assertThat(plan.usedChars()).isLessThanOrEqualTo(16000);
  }

  @Test
  void theFirstChunkIsReservedBeforeAnyPriorTurn() {
    var plan = ContextBudget.plan(1000, 40, List.of(chars(500)), List.of(chars(600)), 2000);
    assertThat(plan.keptChunks()).isEqualTo(1);
    assertThat(plan.keptTurnsFrom()).isEqualTo(1);
    assertThat(plan.usedChars()).isEqualTo(1600);
  }

  @Test
  void nothingIsKeptWhenEvenTheFirstChunkDoesNotFit() {
    var plan = ContextBudget.plan(1500, 40, List.of(chars(10)), List.of(chars(600)), 2000);
    assertThat(plan.keptChunks()).isZero();
    assertThat(plan.squeezedOut()).isEqualTo(1);
  }
}
