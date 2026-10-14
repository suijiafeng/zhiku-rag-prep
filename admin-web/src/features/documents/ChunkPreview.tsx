import { Button, Card, Empty, Typography } from "antd";
import type { DocumentRow } from "./fixtures";

export function ChunkPreview({ document }: { document: DocumentRow | undefined }) {
  return (
    <Card title="切块预览" className="chunk-card">
      {!document ? (
        <Empty description="选择一个文档查看切块" />
      ) : (
        <>
          <Typography.Text type="secondary" className="chunk-sub">
            {document.name} · 共 {document.chunkCount ?? 0} 块
          </Typography.Text>
          {document.status === "parsing" && <Empty description="解析中，暂无切块" />}
          {document.status === "failed" && <Empty description="解析失败，暂无切块；失败原因展示待摄取任务实现" />}
          {document.chunks.map((c) => (
            <div key={c.seq} className="chunk">
              <div className="chunk-head">
                <span className="chunk-seq">#{String(c.seq).padStart(3, "0")}</span>
                <span className="chunk-tokens">{c.tokens} tokens</span>
              </div>
              <div className="chunk-text">{c.text}</div>
            </div>
          ))}
          <div className="chunk-foot">
            <Typography.Text type="secondary">切块策略：待确认（T3）</Typography.Text>
            <Button size="small" disabled title="未实现">查看全部 →</Button>
          </div>
        </>
      )}
    </Card>
  );
}
