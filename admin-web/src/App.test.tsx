import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { App } from "./App";

describe("admin scaffold", () => {
  it("renders its scope and safety boundary", () => {
    const html = renderToStaticMarkup(<App />);
    expect(html).toContain("知库 · 管理端骨架");
    expect(html).toContain("尚未接入生产服务");
    expect(html).toContain("metadata-only");
  });
});
