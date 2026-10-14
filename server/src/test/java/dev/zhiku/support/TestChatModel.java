package dev.zhiku.support;

import dev.zhiku.chat.ChatModel;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** TEST ONLY scripted chat model: emits the scripted answer in small pieces and can run a hook before finishing. */
public class TestChatModel implements ChatModel {
  public volatile Function<List<Message>, String> script = messages -> "年假按司龄计算[1]。";
  public volatile Runnable beforeFinish = () -> {};
  public volatile boolean failMidway = false;
  public volatile List<Message> lastPrompt = List.of();

  public void reset() {
    script = messages -> "年假按司龄计算[1]。";
    beforeFinish = () -> {};
    failMidway = false;
    lastPrompt = List.of();
  }

  @Override
  public String model() {
    return "test-scripted";
  }

  @Override
  public void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
    lastPrompt = messages;
    var answer = script.apply(messages);
    for (int i = 0; i < answer.length(); i += 4) {
      if (cancelled.getAsBoolean()) return;
      onToken.accept(answer.substring(i, Math.min(answer.length(), i + 4)));
      if (failMidway && i > 0) throw new IllegalStateException("model failed (test)");
    }
    beforeFinish.run();
  }
}
