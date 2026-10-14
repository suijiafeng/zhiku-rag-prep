# 知库 RAG · Vibe Coding 准备包
基于产品需求文档 v0.3。先用规则约束小步开发，再实现产品。

## 从这里开始
1. 给编码助手读 `AGENTS.md`（全仓库规范唯一入口）。
2. 读 `docs/architecture.md` 和 `docs/conventions.md`；在 `docs/adr/002-open-decisions.md` 确认阻塞项。
3. 从 `TASKS.md` 只选一个任务，套用 `docs/ai-task-template.md`，明确路径和验收。

## 目录
- server：Java 21 / Spring Boot 模块化单体。JWT 身份、知识库授权、文档版本与效力、持久摄取任务、精确向量检索、SSE 问答、历史依赖重查、挂件短期令牌、追加式审计；Flyway V1–V15。
- admin-web：React + TypeScript + antd 管理端，覆盖设计稿 12 页。登录页为入口；登录后所有页面使用真实接口；未登录时可"以示例数据浏览"。
- widget：独立 React 问答挂件（悬浮按钮、展开面板、引用展开、赞踩、主题色、手机全屏）、SSE 分帧器、streamChat 与 rateMessage。
- contracts/openapi.yaml：已实现接口与 SSE 协议。
- scripts：本地开发令牌签发与虚构示例种子数据（仅开发用）。
- compose.yaml：本地 Postgres/pgvector。
- docs：架构、约定、ADR、任务提示词、验证记录。

## 运行与验证
- 后端：`cd server && mvn -B clean verify`（需要 Docker，集成测试用 Testcontainers 启动 pgvector）。本地运行步骤见 server/README.md。
- 管理端与挂件：各包 `npm ci && npm run check`。管理端 `npm run dev` 通过 Vite 代理访问 127.0.0.1:8080。
- 模型：本地 Ollama。`ZHIKU_CHAT_PROVIDER=ollama` 启用问答；`ZHIKU_EMBEDDING_PROVIDER=ollama` 启用向量化，需要一个支持 `/api/embed` 的 embedding 模型（默认 bge-m3，需自行 `ollama pull`）。未配置 embedding 时文档会切块但标记失败，不可检索。

## 范围与真实状态
已实现并有集成测试：身份与授权（本地 JWT）、文档版本与效力、文档删除（立即停止访问，后台清理内容、切块与向量）、Markdown/TXT 摄取、精确向量检索、SSE 问答与引用元数据检查、撤权与历史依赖重查、挂件短期令牌、问答反馈与问答记录、反馈处理（关闭须有依据：经最近一次检索评测验证或填写理由，处理留痕）、续问上下文（经来源复核的前序对话，非事实依据，依赖随答案记录）、问答请求去重与原请求查询、管理员授权管理、知识库总览统计、以原文区间为真值的检索评测。
未实现：生产 IdP/SSO 与用户目录、对话摘要、词法检索/融合/重排、引用语义验证、回答正确性的人工评测、配置候选/发布流程、长期 API Key 与 Webhook（第二阶段）。
本地签发令牌只用于开发；默认值来源见 ADR-002。没有真实企业材料、没有付费模型调用。
实际执行证据与未验证项见 `docs/verification.md`。
