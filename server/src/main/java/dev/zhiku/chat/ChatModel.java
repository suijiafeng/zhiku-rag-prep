package dev.zhiku.chat;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Port for a streaming chat model. Implementations must time out, stop when cancelled and never log content. */
public interface ChatModel {
  record Message(String role, String content) {}

  String model();

  void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled);
}
