package dev.zhiku.chat;

import java.io.IOException;

/** Event sink for one answer stream; cancelled() turns true once the client disconnects. */
public interface ChatSink {
  void send(String event, Object data) throws IOException;

  boolean cancelled();
}
