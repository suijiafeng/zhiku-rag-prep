import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { App } from "./App";

describe("admin shell", () => {
  const html = renderToStaticMarkup(<App />);

  it("renders the navigation from the design", () => {
    for (const label of ["知识库总览", "文档管理", "问答工作台", "检索调试", "评测中心", "系统设置"]) {
      expect(html).toContain(label);
    }
  });

  it("never labels the scaffold as production", () => {
    expect(html).not.toContain("生产环境");
    expect(html).toContain("示例数据");
    expect(html).toContain("尚未接入生产服务");
    expect(html).toContain("metadata-only");
  });

  it("keeps unimplemented entry points disabled", () => {
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*新建问答/s);
    expect(html).toMatch(/<input[^>]*disabled/);
  });

  it("routes the documents nav item to a real page, not a placeholder", async () => {
    const { pageFor } = await import("./App");
    expect(renderToStaticMarkup(pageFor("documents"))).toContain("文档管理");
    expect(renderToStaticMarkup(pageFor("documents"))).not.toContain("待实现");
    expect(renderToStaticMarkup(pageFor("qa"))).toContain("问答工作台");
    expect(renderToStaticMarkup(pageFor("debug"))).toContain("检索调试");
    expect(renderToStaticMarkup(pageFor("debug"))).not.toContain("待实现");
    expect(renderToStaticMarkup(pageFor("evaluation"))).toContain("评测中心");
    expect(renderToStaticMarkup(pageFor("evaluation"))).not.toContain("待实现");
    expect(renderToStaticMarkup(pageFor("settings"))).toContain("待实现");
  });
});
