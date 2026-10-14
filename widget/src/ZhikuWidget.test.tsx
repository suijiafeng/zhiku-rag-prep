import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ZhikuWidget, normalizeTheme } from "./ZhikuWidget.js";

describe("widget", () => {
  it("starts collapsed as a floating button without fetching a token", () => {
    const getAccessToken = vi.fn(async () => "short-lived-placeholder");
    const html = renderToStaticMarkup(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-1" getAccessToken={getAccessToken} />);
    expect(html).toContain('aria-label="打开知库问答"');
    expect(html).not.toContain("<textarea");
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it("opens to the panel with greeting and quick questions", () => {
    const html = renderToStaticMarkup(
      <ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb-1" defaultOpen quickQuestions={["年假有几天?", "报销流程"]} />,
    );
    expect(html).toContain("你好");
    expect(html).toContain("年假有几天?");
    expect(html).toContain("<textarea");
    expect(html).toContain('aria-label="关闭"');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>发送/);
  });

  it("accepts only hex theme colours", () => {
    expect(normalizeTheme("#1D5BBF")).toBe("#1D5BBF");
    expect(normalizeTheme("#abc")).toBe("#abc");
    expect(normalizeTheme("red;background:url(x)")).toBe("#2f5bd3");
    expect(normalizeTheme(undefined)).toBe("#2f5bd3");
    const html = renderToStaticMarkup(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb" defaultOpen themeColor="url(evil)" />);
    expect(html).toContain("--zhiku-primary:#2f5bd3");
  });

  it("escapes host-provided text with normal React rendering", () => {
    const html = renderToStaticMarkup(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb" defaultOpen title="<script>unsafe()</script>" />);
    expect(html).not.toContain("<script>unsafe");
    expect(html).toContain("&lt;script&gt;");
  });

  it("keeps all styles scoped and touches no browser storage", () => {
    const html = renderToStaticMarkup(<ZhikuWidget endpoint="/api/chat" knowledgeBaseId="kb" />);
    const css = /<style>([\s\S]*?)<\/style>/.exec(html)?.[1] ?? "";
    const selectors = [...css.matchAll(/([^{}]+)\{/g)].map((m) => m[1]!.trim()).filter((sel) => !sel.startsWith("@"));
    expect(selectors.length).toBeGreaterThan(5);
    for (const group of selectors) for (const sel of group.split(",")) expect(sel.trim()).toMatch(/^\.zhiku-/);
    expect(ZhikuWidget.toString()).not.toMatch(/localStorage|sessionStorage|document\.cookie/);
  });
});
