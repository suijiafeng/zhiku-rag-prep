import { Tag } from "antd";

/** Parsing/index state only. Business approval, publication and effectivity are separate facts. */
export type IndexStatus = "pending" | "indexed" | "parsing" | "failed";

const tags: Record<IndexStatus, { color: string; label: string }> = {
  pending: { color: "default", label: "待处理" },
  indexed: { color: "success", label: "已索引" },
  parsing: { color: "warning", label: "解析中" },
  failed: { color: "error", label: "失败" },
};

export const indexStatusLabel = (s: IndexStatus) => tags[s].label;

export function IndexStatusTag({ status }: { status: IndexStatus }) {
  return <Tag color={tags[status].color}>{tags[status].label}</Tag>;
}
