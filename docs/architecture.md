# 架构边界 · PRD v0.3

## 当前存在
- server：单个 Spring Boot 应用，包 auth / knowledgebase / document / ingestion / embedding / retrieval / chat / audit / platform。
  - 身份：JWT 资源服务器；授权库每请求从数据库读取；挂件令牌限单库且只能调用 /api/chat。
  - 数据：Flyway V1–V11（知识库与授权、文档与内容版本、内容与摄取任务、切块、向量、会话与来源依赖、审计、反馈、评测、问答结果状态、文档删除、反馈处理记录）。
  - 删除：标记删除即退出资格、检索、引用、评测证据与总览统计，依赖它的历史回答隐藏；数据库租约任务随后清理原文、切块、向量与摄取任务，状态 pending/running/done/failed 可查，失败只写 ERROR 日志，无外部告警；备份不在清理范围内。
  - 摄取：数据库租约队列（FOR UPDATE SKIP LOCKED），租约内一次性写入切块与向量；无 @Async。
  - 检索：先按授权与效力得出合格版本，再在同一 embedding 模型与维度内做精确余弦检索；无 ANN、无融合、无重排。
  - 问答：SSE draft → validated → done；披露前检查引用编号并重读授权与效力；只保存完成的回答。
- admin-web：管理端，示例模式与已连接模式；令牌只在页面内存。
- widget：可嵌入问答组件。
未实现：生产 IdP、对话摘要、引用语义验证、回答正确性人工评测、配置候选/发布流程。反馈关闭的回归验证只检查检索召回，不检查回答。

## 目标模块依赖（设计约束，非实现清单）
单体按业务包：auth → 身份/权限；knowledgebase → 授权库；document → 内容版本/业务效力；ingestion → 持久任务与解析；retrieval → 合格版本内检索；chat → 编排；citation → 来源解析；evaluation → 独立真值与回归；audit → 脱敏追加事件。
箭头为依赖方向：controller → application → domain；infrastructure 实现 application 的 port。domain 不依赖 Spring/HTTP/数据库/模型 SDK。模块之间只调公开应用接口/事件，不跨模块读 Repository。shared 只放极小通用值类型，禁止万能 util 或基础业务 Service。
chat 依赖 auth、document 的资格选择、retrieval 和 citation 的公开接口；retrieval 不反向依赖 chat。身份信息由服务端建立。审核通过但未生效、当前生效、索引未就绪是独立事实。

## 数据/检索关键边界
未来 migration 应区分 document、content_version、business_effectivity、index_version、ingestion_job、source_dependency。ingestion_job 持久落库/租约/幂等完成后才可声称可恢复任务；@Async 不是队列。
先确认主题、受众、业务时区和替代关系，再在授权有效版本集合内检索。示例策略仅覆盖一个已确认主题/适用范围；不实现替代关系图或完整业务引擎。不得直接用于生产判定。
embedding 维度不写死：每行记录模型与维度，检索只比较同一空间。没有模型不生成假向量（测试用确定性测试向量，仅存在于测试代码）。中文词法、ANN、reranker 以后用评测证明收益。
