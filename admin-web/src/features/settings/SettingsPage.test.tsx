import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { SettingsPage } from "./SettingsPage";
import type { Status } from "../../api/client";

const status: Status = {
  name: "知库", stage: "development", prdVersion: "0.3", modelEnabled: true, chatModel: "ollama:qwen3.5:latest",
  embeddingModel: null, retrieval: "exact-vector", businessTimeZone: "Asia/Shanghai", maxUploadBytes: 20971520,
  uploadFormats: [".md", ".markdown", ".txt"],
};

describe("settings page", () => {
  it("shows the real server configuration read-only", () => {
    const html = renderToStaticMarkup(<SettingsPage status={status} error={undefined} />);
    expect(html).toContain("ollama:qwen3.5:latest");
    expect(html).toContain("未配置");
    expect(html).toContain("Asia/Shanghai");
    expect(html).toContain("20 MB");
    expect(html).toContain("只读");
  });

  it("offers no destructive or silent re-embedding action", () => {
    const html = renderToStaticMarkup(<SettingsPage status={status} error={undefined} />);
    expect(html).not.toContain("清空向量库");
    expect(html).not.toMatch(/<button[^>]*>(?:(?!<\/button>).)*保\s*存/s);
  });

  it("explains an unreachable server", () => {
    expect(renderToStaticMarkup(<SettingsPage status={undefined} error="HTTP_502" />)).toContain("无法读取服务状态");
  });
});
