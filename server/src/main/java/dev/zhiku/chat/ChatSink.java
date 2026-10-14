package dev.zhiku.chat;

import java.io.IOException;

/** 单个回答流的事件接收端；客户端断开后 cancelled() 变为 true。 */
public interface ChatSink {
  void send(String event, Object data) throws IOException;

  boolean cancelled();
}
