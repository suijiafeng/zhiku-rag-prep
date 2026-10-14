import { describe, expect, it } from "vitest";
import { createSseParser } from "./sse.js";

describe("SSE transport parser", () => {
  it("handles arbitrary chunks and split CRLF separators", () => {
    const parser = createSseParser();
    expect(parser.push('event: draft\r')).toEqual([]);
    expect(parser.push('\ndata: {"text":"你好"}\r\n\r')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: "draft", data: '{"text":"你好"}' }]);
  });

  it("keeps multiline data and resets event names", () => {
    const parser = createSseParser();
    expect(parser.push(": heartbeat\nevent: draft\ndata: first\ndata: second\n\ndata: next\n\n"))
      .toEqual([{ event: "draft", data: "first\nsecond" }, { event: "message", data: "next" }]);
  });

  it("ignores comments, unknown fields and records without data", () => {
    const parser = createSseParser();
    expect(parser.push("id: 1\nretry: 1000\n: pulse\nevent: draft\n\ndata:\n\n"))
      .toEqual([{ event: "message", data: "" }]);
  });

  it("supports BOM and CR-only framing including terminal CR", () => {
    const parser = createSseParser();
    expect(parser.push("\uFEFFdata: value\r\r")).toEqual([]);
    expect(parser.finish()).toEqual([{ event: "message", data: "value" }]);
  });

  it("does not parse JSON or treat untrusted payloads as HTML", () => {
    const parser = createSseParser();
    expect(parser.push("event: unknown\ndata: <script>unsafe()</script>\n\n"))
      .toEqual([{ event: "unknown", data: "<script>unsafe()</script>" }]);
  });

  it("discards an incomplete final event and closes after finish", () => {
    const parser = createSseParser();
    parser.push("data: incomplete\n");
    expect(parser.finish()).toEqual([]);
    expect(parser.finish()).toEqual([]);
    expect(() => parser.push("data: later\n\n")).toThrow("closed");
  });

  it("caps unclosed records and partial lines", () => {
    expect(() => createSseParser(4).push("data: too long")).toThrow("size limit");
    expect(() => createSseParser(9).push("data: a\ndata: b\n")).toThrow("size limit");
    expect(() => createSseParser(0)).toThrow("positive safe integer");
  });
});
