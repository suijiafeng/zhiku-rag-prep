import { Button, Card, Col, Row, Segmented, Table, Typography } from "antd";
import { useState } from "react";
import { IndexStatusTag, indexStatusLabel, type IndexStatus } from "../../components/IndexStatusTag";
import { ChunkPreview } from "./ChunkPreview";
import type { DocumentRow, DocumentsData } from "./fixtures";

type Filter = "all" | IndexStatus;

export function countByStatus(docs: DocumentRow[]) {
  const counts = { all: docs.length, indexed: 0, parsing: 0, failed: 0 };
  for (const d of docs) counts[d.status] += 1;
  return counts;
}

export function filterDocuments(docs: DocumentRow[], filter: Filter) {
  return filter === "all" ? docs : docs.filter((d) => d.status === filter);
}

/** The preview always follows a visible row: a filtered-out selection falls back to the first row. */
export function visibleSelection(rows: DocumentRow[], selectedId: string | undefined) {
  return rows.find((d) => d.id === selectedId) ?? rows[0];
}

const extension = (name: string) => name.slice(name.lastIndexOf(".") + 1).toLowerCase();

export function DocumentsPage({ data }: { data: DocumentsData }) {
  const [filter, setFilter] = useState<Filter>("all");
  const [selectedId, setSelectedId] = useState(data.documents[0]?.id);
  const counts = countByStatus(data.documents);
  const rows = filterDocuments(data.documents, filter);
  const selected = visibleSelection(rows, selectedId);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">文档管理</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            共 {counts.all} 个示例文档 · 允许格式待确认（T3）
          </Typography.Text>
        </div>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <div className="upload-zone">
            <span className="upload-icon" aria-hidden="true">↑</span>
            <div>
              拖拽文件到此处，或 <Button type="primary" size="small" disabled title="上传尚未实现">选择文件</Button>
            </div>
            <Typography.Text type="secondary" className="page-sub">
              允许格式、单文件上限与样本准入待确认（D06 / T3）；上传与解析任务尚未实现
            </Typography.Text>
          </div>

          <Segmented
            className="doc-filter"
            value={filter}
            onChange={(v) => setFilter(v as Filter)}
            options={[
              { value: "all", label: `全部 (${counts.all})` },
              ...(["indexed", "parsing", "failed"] as const).map((s) => ({
                value: s,
                label: `${indexStatusLabel(s)} (${counts[s]})`,
              })),
            ]}
          />

          <Card className="doc-table">
            <Table
              size="middle"
              pagination={false}
              rowKey="id"
              dataSource={rows}
              scroll={{ x: 640 }}
              rowClassName={(r) => (r.id === selected?.id ? "row-selected" : "")}
              onRow={(r) => ({ onClick: () => setSelectedId(r.id) })}
              columns={[
                {
                  title: "文档",
                  dataIndex: "name",
                  render: (name: string) => (
                    <span className="doc-name">
                      <span className={`doc-type doc-type-${extension(name)}`} aria-hidden="true" />
                      {name}
                    </span>
                  ),
                },
                { title: "大小", dataIndex: "size", className: "mono" },
                { title: "切块", dataIndex: "chunkCount", className: "mono", render: (v: number | null) => v ?? "—" },
                { title: "向量", dataIndex: "vectorCount", className: "mono", render: (v: number | null) => v ?? "—" },
                { title: "索引状态", dataIndex: "status", render: (s: IndexStatus) => <IndexStatusTag status={s} /> },
                { title: "更新时间", dataIndex: "updatedAt", className: "mono" },
                {
                  title: "操作",
                  key: "actions",
                  render: () => <Button type="text" size="small" disabled title="操作尚未实现" aria-label="更多操作">···</Button>,
                },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <ChunkPreview document={selected} />
        </Col>
      </Row>
    </div>
  );
}
