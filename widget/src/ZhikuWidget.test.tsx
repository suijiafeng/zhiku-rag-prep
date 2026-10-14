import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ZhikuWidget } from "./ZhikuWidget.js";

describe("widget scaffold", () => {
  it("renders the isolated shell without retrieving tokens", () => {
    const getAccessToken = vi.fn(async () => "short-lived-placeholder");
    const html = renderToStaticMarkup(
      <ZhikuWidget endpoint="/future/chat" knowledgeBaseId="demo" getAccessToken={getAccessToken} />,
    );
    expect(html).toContain("问答功能尚未接入");
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it("escapes host-provided text with normal React rendering", () => {
    const html = renderToStaticMarkup(
      <ZhikuWidget endpoint="/future/chat" knowledgeBaseId="demo" title="<script>unsafe()</script>" />,
    );
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
  });
});
