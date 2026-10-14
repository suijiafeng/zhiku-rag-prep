package dev.zhiku.chat;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 流式聊天模型的端口。实现必须有超时、取消时停止，且绝不记录内容。 */
public interface ChatModel {
  record Message(String role, String content) {}

  String model();

  void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled);
}
