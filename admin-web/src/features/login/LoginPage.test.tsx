import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { LoginPage } from "./LoginPage";
import { App } from "../../App";

describe("login page", () => {
  const html = renderToStaticMarkup(<LoginPage onLogin={async () => undefined} onBrowseSamples={() => {}} />);

  it("does not promise login methods that do not exist", () => {
    expect(html).not.toContain('type="password"><');
    expect(html).not.toContain("记住我");
    expect(html).not.toContain("忘记密码");
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*企业身份登录/s);
  });

  it("does not advertise capabilities the system lacks", () => {
    for (const claim of ["混合检索", "Rerank", "92.4%", "BM25"]) expect(html).not.toContain(claim);
    expect(html).toContain("授权范围");
  });

  it("offers dev-token login and sample browsing", () => {
    expect(html).toContain("开发令牌");
    expect(html).toContain("以示例数据浏览");
  });

  it("is the first screen of the app", () => {
    expect(renderToStaticMarkup(<App />)).toContain("登录管理控制台");
  });
});
