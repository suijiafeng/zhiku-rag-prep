# 架构边界 · PRD v0.3

## 当前存在
- server：单个 Spring Boot 应用。公开的无敏感信息 `/api/status`；其余路径拒绝。纯策略示例 AccessScope、VersionEligibility 及测试。
- admin-web：React/TypeScript/antd 静态冒烟页。
- widget：独立 React 库壳、草案 SSE 类型/解析测试，无业务流程。
- compose：本地 PostgreSQL 16 + pgvector；仅基础设施。迁移文件尚未接入 Flyway。
没有模型调用、真实向量、用户登录、上传、任务执行器、聊天、引用验证器、评测系统或审计持久化。

## 目标模块依赖（设计约束，非实现清单）
单体按业务包：auth → 身份/权限；knowledgebase → 授权库；document → 内容版本/业务效力；ingestion → 持久任务与解析；retrieval → 合格版本内检索；chat → 编排；citation → 来源解析；evaluation → 独立真值与回归；audit → 脱敏追加事件。
箭头为依赖方向：controller → application → domain；infrastructure 实现 application 的 port。domain 不依赖 Spring/HTTP/数据库/模型 SDK。模块之间只调公开应用接口/事件，不跨模块读 Repository。shared 只放极小通用值类型，禁止万能 util 或基础业务 Service。
chat 依赖 auth、document 的资格选择、retrieval 和 citation 的公开接口；retrieval 不反向依赖 chat。身份信息由服务端建立。审核通过但未生效、当前生效、索引未就绪是独立事实。

## 数据/检索关键边界
未来 migration 应区分 document、content_version、business_effectivity、index_version、ingestion_job、source_dependency。ingestion_job 持久落库/租约/幂等完成后才可声称可恢复任务；@Async 不是队列。
先确认主题、受众、业务时区和替代关系，再在授权有效版本集合内检索。示例策略仅覆盖一个已确认主题/适用范围；不实现替代关系图或完整业务引擎。不得直接用于生产判定。
embedding provider/model/revision/dimension/metric 全部待确认。禁止先建 vector(1536) 等猜测维度；没有模型不生成假向量。中文词法、ANN、reranker 以后用评测证明收益。
