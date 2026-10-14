/** 仅为传输帧。JSON 数据在单独校验前均不可信。 */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * 最小化的增量 SSE 解析器：支持 event/data 字段、注释及 LF/CRLF/CR 换行。
 * 有意忽略 id/retry 字段：未实现重连。
 * 应传入同一个持久 TextDecoder 以 { stream: true } 解码出的文本。
 */
export function createSseParser(maxEventChars = 1_048_576) {
  if (!Number.isSafeInteger(maxEventChars) || maxEventChars <= 0) {
    throw new Error("maxEventChars must be a positive safe integer");
  }
  let buffer = "";
  let event = "message";
  let data: string[] = [];
  let recordChars = 0;
  let start = true;
  let ended = false;

  function drain(final: boolean): SseFrame[] {
    const frames: SseFrame[] = [];
    while (true) {
      const end = buffer.search(/[\r\n]/u);
      if (end < 0 || (!final && buffer[end] === "\r" && end === buffer.length - 1)) break;
      const line = buffer.slice(0, end);
      const newlineWidth = buffer[end] === "\r" && buffer[end + 1] === "\n" ? 2 : 1;
      buffer = buffer.slice(end + newlineWidth);
      recordChars += line.length;
      if (recordChars > maxEventChars) throw new Error("SSE event exceeds size limit");
      if (line === "") {
        if (data.length > 0) frames.push({ event, data: data.join("\n") });
        event = "message";
        data = [];
        recordChars = 0;
      } else if (!line.startsWith(":")) {
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value || "message";
        if (field === "data") data.push(value);
      }
    }
    if (recordChars + buffer.length > maxEventChars) throw new Error("SSE event exceeds size limit");
    return frames;
  }

  return {
    push(chunk: string): SseFrame[] {
      if (ended) throw new Error("SSE parser is closed");
      if (start && chunk.length > 0) {
        chunk = chunk.replace(/^\uFEFF/u, "");
        start = false;
      }
      buffer += chunk;
      return drain(false);
    },
    finish(): SseFrame[] {
      if (ended) return [];
      ended = true;
      const frames = drain(true);
      // SSE 规范会丢弃未以空行结束的记录。
      buffer = "";
      data = [];
      return frames;
    },
  };
}
