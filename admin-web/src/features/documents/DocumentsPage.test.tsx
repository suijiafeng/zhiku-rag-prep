import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { ChunkPreview } from "./ChunkPreview";
import { DocumentsPage, countByStatus, filterDocuments, visibleSelection } from "./DocumentsPage";
import { documentsFixture } from "./fixtures";

const docs = documentsFixture.documents;

describe("documents page", () => {
  const html = renderToStaticMarkup(<DocumentsPage data={documentsFixture} />);

  it("lists every fixture document", () => {
    for (const d of docs) expect(html).toContain(d.name);
  });

  it("derives filter counts from the data instead of hard-coded totals", () => {
    const counts = countByStatus(docs);
    expect(counts.all).toBe(docs.length);
    expect(counts.indexed + counts.parsing + counts.failed).toBe(docs.length);
    expect(html).toContain(`全部 (${docs.length})`);
    expect(html).not.toContain("1,284");
  });

  it("filters by index status", () => {
    expect(filterDocuments(docs, "all")).toHaveLength(docs.length);
    for (const s of ["indexed", "parsing", "failed"] as const) {
      const rows = filterDocuments(docs, s);
      expect(rows.length).toBeGreaterThan(0);
      expect(rows.every((d) => d.status === s)).toBe(true);
    }
  });

  it("never previews a document hidden by the current filter", () => {
    const failed = filterDocuments(docs, "failed");
    expect(visibleSelection(failed, docs[0]!.id)?.id).toBe(failed[0]!.id);
    expect(visibleSelection(docs, docs[1]!.id)?.id).toBe(docs[1]!.id);
    expect(visibleSelection([], docs[0]!.id)).toBeUndefined();
  });

  it("keeps upload disabled and does not invent ingestion limits", () => {
    expect(html).toMatch(/<button[^>]*disabled[^>]*>(?:(?!<\/button>).)*选择文件/s);
    expect(html).not.toContain("50 MB");
    expect(html).not.toContain("512 tokens，重叠");
    expect(html).toContain("待确认");
  });

  it("labels the column as index status, separate from business effectivity", () => {
    expect(html).toContain("索引状态");
  });
});

describe("chunk preview", () => {
  const withChunks = docs.find((d) => d.chunks.length > 0)!;

  it("shows chunks of the selected document", () => {
    const html = renderToStaticMarkup(<ChunkPreview document={withChunks} />);
    for (const c of withChunks.chunks) expect(html).toContain(c.text);
  });

  it("explains empty states for parsing and failed documents", () => {
    const parsing = docs.find((d) => d.status === "parsing")!;
    const failed = docs.find((d) => d.status === "failed")!;
    expect(renderToStaticMarkup(<ChunkPreview document={parsing} />)).toContain("解析中");
    expect(renderToStaticMarkup(<ChunkPreview document={failed} />)).toContain("解析失败");
  });

  it("asks for a selection when nothing is selected", () => {
    expect(renderToStaticMarkup(<ChunkPreview document={undefined} />)).toContain("选择一个文档");
  });

  it("escapes untrusted chunk text", () => {
    const hostile = { ...withChunks, chunks: [{ ...withChunks.chunks[0]!, text: "<script>x()</script>" }] };
    const html = renderToStaticMarkup(<ChunkPreview document={hostile} />);
    expect(html).not.toContain("<script>");
  });
});
