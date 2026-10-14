# 知库独立 React Widget 骨架

独立 ESM + TypeScript 类型包。当前仅包含组件占位、临时契约类型和 SSE 文本分帧器；没有完整对话、网络请求、模型接入、引用真实性验证或生产鉴权。组件不会调用 getAccessToken。

## 验收与构建

要求 Node.js >= 22.12（建议 Node 24 LTS）及 npm。

```sh
cd widget
npm ci
npm run typecheck
npm test
npm run build
# 或一次运行
npm run check
```

构建输出到 dist/，依赖 React 19 宿主。React/React DOM 是 peerDependencies，不内嵌于包。package.json 标记 private，防止意外发布。测试使用静态渲染与纯解析器单测，不等同于浏览器联调。

## 未来宿主集成示例

在宿主配置本地依赖 `"@zhiku/widget": "file:../widget"` 后先构建本包，再从公共入口导入：

```tsx
import { ZhikuWidget } from "@zhiku/widget";

<ZhikuWidget
  endpoint="/future/chat"
  knowledgeBaseId="demo"
  title="知库助手"
/>
```

endpoint 与 knowledgeBaseId 目前是预留接口，不会触发请求。/future/chat 只是宿主集成占位值；服务端没有聊天路由，当前只有 GET /api/status。后续可传 getAccessToken: () => Promise<string | undefined>，由宿主按请求获取短期、最小权限 token；不要把模型密钥或长期 token 放到前端。包内不提供 token 存储、自动重连或全局样式。生产只允许受信 HTTPS 后端。

## SSE 约定（临时）

以根目录 API 契约为最终依据。预留 POST JSON `{question, kbId}`，SSE 事件：

- draft：`{text}`，临时输出
- validated：`{citations: [{documentId, versionId, title, excerpt}], validation: "metadata-only"}`
- done：`{status: "complete"}`
- error：`{code, message, requestId}`

metadata-only 只描述元数据检查，不能当作真实性、可溯源语义或回答正确性的保证。

createSseParser 仅解析 event/data 分帧，输出字符串；不会执行 HTML、解析 JSON 或验证契约。调用 push(chunk) 输入解码后的增量文本，finish() 结束；没有空行终止的事件将丢弃。支持 LF/CRLF/CR、多行 data、注释及首字符 BOM，忽略 id/retry/未知字段。每条记录默认上限 1,048,576 个 JavaScript 字符，超过会抛错。

## 问答客户端 streamChat

`streamChat({ endpoint, request, getAccessToken, signal, onEvent })` 按上述草案契约发起 POST + SSE，返回 `complete`、`failed` 或 `cancelled` 三种结果之一：

- 每次请求调用 getAccessToken 取短期令牌放入 Authorization，不存储、不记录；`credentials: "omit"`，不带宿主 Cookie。
- 检查 HTTP 状态与 `text/event-stream`；非 2xx 时仅在 JSON 错误体符合 `{code, message, requestId}` 时采用，否则返回 `HTTP_<状态码>`。
- 持久 TextDecoder 流式解码，逐帧运行时校验 JSON；未知事件名忽略，已知事件结构不符即 `PROTOCOL` 失败。
- 只有先 validated 后 done 才算 complete；error、取消、协议违规、流提前结束（`STREAM_INCOMPLETE`）都丢弃回答。onEvent 收到的 draft 是临时内容，结果不是 complete 时调用方必须丢弃。
- 从不自动重试，避免重复 POST 造成重复计费。

服务端尚无聊天路由；组件 ZhikuWidget 目前仍不调用 streamChat。

## 依赖基线

2026-10-14 通过官方 npm registry 核对 React/React DOM 19.3.0、对应类型 19.3.0、Vitest 5.0.4 和 TypeScript 5.9.3。直接依赖固定版本，package-lock.json 锁定依赖树。可从 <https://registry.npmjs.org/react>、<https://registry.npmjs.org/vitest> 和 <https://registry.npmjs.org/typescript> 复核。

编码规则统一见仓库根 AGENTS.md 和 docs/conventions.md。

SSE 分帧语义参考 [WHATWG HTML Standard](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)。本包仅实现上文声明的子集，不包含 EventSource 自动重连行为。
