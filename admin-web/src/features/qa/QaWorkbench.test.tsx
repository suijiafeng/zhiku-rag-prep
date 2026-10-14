import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { AnswerText } from "./AnswerText";
import { QaWorkbench } from "./QaWorkbench";
import { qaFixture } from "./fixtures";

describe("qa workbench", () => {
  const html = renderToStaticMarkup(<QaWorkbench data={qaFixture} />);

  it("renders history, the sample answer and its sources", () => {
    for (const s of qaFixture.sessions) expect(html).toContain(s.title);
    for (const src of qaFixture.sources) expect(html).toContain(src.title);
    expect(html).toContain(qaFixture.sample.question);
  });

  it("keeps sending disabled because no chat route exists", () => {
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*发\s*送/s);
    expect(html).toMatch(/<textarea[^>]*disabled/);
    expect(html).toContain("问答服务未接入");
  });

  it("does not claim a model or retrieval strategy pending ADR-002 and T4", () => {
    expect(html).not.toContain("glm");
    expect(html).not.toContain("混合检索开");
    expect(html).not.toContain("TopK 8");
  });

  it("shows the metadata-only validation wording, not a correctness claim", () => {
    expect(html).toContain("来源信息检查通过，请核对内容");
  });

  it("labels the streaming demo as an offline replay", () => {
    expect(html).toContain("离线回放");
  });
});

describe("answer text", () => {
  it("turns citation markers into buttons bound to their source", () => {
    const html = renderToStaticMarkup(<AnswerText text="甲[1]乙[2]" citationCount={2} onCite={() => {}} />);
    expect(html.match(/<button/g)).toHaveLength(2);
    expect(html).toContain('aria-label="来源 1"');
  });

  it("leaves markers without a matching citation as plain text", () => {
    const html = renderToStaticMarkup(<AnswerText text="甲[3]" citationCount={2} onCite={() => {}} />);
    expect(html).not.toContain("<button");
    expect(html).toContain("[3]");
  });

  it("escapes model output instead of rendering HTML", () => {
    const html = renderToStaticMarkup(<AnswerText text="<img src=x onerror=1>[1]" citationCount={1} onCite={() => {}} />);
    expect(html).not.toContain("<img");
  });
});
