// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient, DocumentInfo } from "../../api/client";
import { LiveDocuments } from "./LiveDocuments";

const doc: DocumentInfo = { id: "d", title: "虚构文档", createdAt: "2030-01-01T00:00:00Z", versions: [{
  id: "v", versionLabel: "v1", effectiveFrom: "2030-01-01T00:00:00Z", effectiveUntil: null, approved: true, published: true,
  withdrawn: false, superseded: false, indexStatus: "ready", indexError: null,
}] };
function clientWith(docs: DocumentInfo[]): ApiClient {
  return { documents: vi.fn(async () => docs), transition: vi.fn(async () => {}) } as unknown as ApiClient;
}
const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });
afterEach(cleanup);

describe("documents page", () => {
  it("names every condition a version needs before it can answer questions", async () => {
    render(<LiveDocuments client={clientWith([])} kbId="kb" status={undefined} />);
    expect(await screen.findByText(/批准、发布、处于有效期且索引就绪/)).toBeTruthy();
    expect(screen.queryByText(/解析完成后即可问答/)).toBeNull();
  });

  it("withdraws a version only after an explicit confirmation", async () => {
    const client = clientWith([doc]);
    render(<LiveDocuments client={client} kbId="kb" status={undefined} />);
    fireEvent.click(await screen.findByRole("button", { name: /展开行|Expand row/ }));
    fireEvent.click(await screen.findByRole("button", named("撤回")));
    expect(client.transition).not.toHaveBeenCalled();
    expect(await screen.findByText("撤回该版本？")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", named("确认撤回")));
    await waitFor(() => expect(client.transition).toHaveBeenCalledWith("kb", "d", "v", "withdraw"));
  });
});
