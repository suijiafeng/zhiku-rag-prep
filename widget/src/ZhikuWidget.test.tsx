import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ZhikuWidget } from "./ZhikuWidget.js";

describe("widget", () => {
  it("renders a question form without fetching a token until the user sends", () => {
    const getAccessToken = vi.fn(async () => "short-lived-placeholder");
    const html = renderToStaticMarkup(
      <ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-1" getAccessToken={getAccessToken} />,
    );
    expect(html).toContain("<textarea");
    expect(html).toContain("可核对的来源");
    expect(html).toMatch(/<button[^>]*disabled[^>]*>发送/);
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it("escapes host-provided text with normal React rendering", () => {
    const html = renderToStaticMarkup(
      <ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-1" title="<script>unsafe()</script>" />,
    );
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
  });

  it("does not touch browser storage", () => {
    const source = ZhikuWidget.toString();
    expect(source).not.toMatch(/localStorage|sessionStorage|document\.cookie/);
  });
});
