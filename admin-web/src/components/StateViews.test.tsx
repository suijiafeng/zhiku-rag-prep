import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { EmptyState, ErrorState, LoadingList, errorTitle } from "./StateViews";

describe("state views", () => {
  it("maps error codes to user-facing titles without inventing an upgrade path", () => {
    expect(errorTitle("DEPENDENCY_UNAVAILABLE")).toBe("依赖服务暂不可用");
    expect(errorTitle("MODEL_UNAVAILABLE")).toBe("依赖服务暂不可用");
    expect(errorTitle("HTTP_429")).toBe("请求过多");
    expect(errorTitle("FORBIDDEN")).toBe("没有访问权限");
    expect(errorTitle("WHATEVER")).toBe("请求失败");
    const html = renderToStaticMarkup(<ErrorState code="HTTP_429" message="Too many" requestId="r-1" onRetry={() => {}} />);
    expect(html).not.toContain("升级");
    expect(html).toContain("r-1");
    expect(html).toMatch(/重\s*试/);
  });

  it("offers retry only when the caller supplies one", () => {
    expect(renderToStaticMarkup(<ErrorState code="FORBIDDEN" message="Access denied" />)).not.toMatch(/重\s*试/);
  });

  it("renders empty states with their action and a loading skeleton", () => {
    const html = renderToStaticMarkup(<EmptyState icon="doc" title="暂无文档" description="先新建文档" action={<button>新建文档</button>} />);
    expect(html).toContain("暂无文档");
    expect(html).toContain("新建文档");
    expect(renderToStaticMarkup(<LoadingList />)).toContain("ant-skeleton");
  });

  it("names the newer refusal and stop codes", () => {
    expect(errorTitle("SENSITIVE_INPUT")).toBe("问题含个人敏感信息");
    expect(errorTitle("ANSWER_TOO_LONG")).toBe("回答超出长度上限");
    expect(errorTitle("CITATION_INVALID")).toBe("引用无法核对");
  });
});
