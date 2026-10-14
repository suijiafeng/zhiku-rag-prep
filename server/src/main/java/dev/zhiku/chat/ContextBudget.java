package dev.zhiku.chat;

import dev.zhiku.retrieval.Retrieval.Candidate;
import java.util.ArrayList;
import java.util.List;

/**
 * 入模内容的总字符预算。每块各自有上限不等于合计有上限：按得分顺序纳入证据块，超出预算的块被挤出，
 * 第一块始终保留。挤出数量记入轨迹，便于解释为什么引用少于配置的块数。
 */
final class ContextBudget {
  record Selection(List<Candidate> kept, int squeezedOut) {}

  private ContextBudget() {}

  static int chunkChars(Candidate c, int maxChunkChars) {
    return Math.min(c.text().length(), maxChunkChars);
  }

  static Selection select(List<Candidate> ranked, int priorChars, int maxChunkChars, int budget) {
    var kept = new ArrayList<Candidate>();
    int used = priorChars;
    for (var c : ranked) {
      int size = chunkChars(c, maxChunkChars);
      if (!kept.isEmpty() && used + size > budget) break;
      kept.add(c);
      used += size;
    }
    return new Selection(kept, ranked.size() - kept.size());
  }
}
