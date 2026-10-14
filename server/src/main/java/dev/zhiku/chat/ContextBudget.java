package dev.zhiku.chat;

import java.util.List;

/**
 * 一次送入模型的全部内容的字符上限：系统提示、补充规则、当前问题、前序对话和证据块（含标题、版本与包围标记）都计入。
 * 先给第一块证据留出位置，再从最近一轮开始纳入前序对话，最后按得分顺序纳入其余证据块；放不下的都不送入。
 * 没有例外：连第一块都放不下时不纳入任何证据，由调用方停止。字符数不等同于 token 数。
 */
final class ContextBudget {
  /** keptTurnsFrom：从该下标起的前序轮次被纳入（等于轮次数表示一轮也没有）；keptChunks：纳入前几块证据。 */
  record Plan(int keptTurnsFrom, int droppedTurns, int keptChunks, int squeezedOut, int usedChars) {}

  private ContextBudget() {}

  /**
   * @param fixedChars 系统提示、补充规则、问题与固定标签的长度
   * @param turnHeaderChars 至少纳入一轮前序对话时额外出现的说明文字长度
   * @param turns 按时间从旧到新、已序列化的前序轮次
   * @param chunks 按得分排序、已序列化的证据块
   */
  static Plan plan(int fixedChars, int turnHeaderChars, List<String> turns, List<String> chunks, int budget) {
    int room = budget - fixedChars;
    if (chunks.isEmpty() || chunks.getFirst().length() > room) {
      return new Plan(turns.size(), turns.size(), 0, chunks.size(), fixedChars);
    }
    int used = chunks.getFirst().length();
    int from = turns.size();
    for (int i = turns.size() - 1; i >= 0; i--) {
      int extra = turns.get(i).length() + (from == turns.size() ? turnHeaderChars : 0);
      if (used + extra > room) break;
      used += extra;
      from = i;
    }
    int kept = 1;
    while (kept < chunks.size() && used + chunks.get(kept).length() <= room) used += chunks.get(kept++).length();
    return new Plan(from, from, kept, chunks.size() - kept, fixedChars + used);
  }
}
