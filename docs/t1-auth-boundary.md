# T1 身份与知识库授权边界 · 接口与测试计划
状态：2026-10-14 完成"无输入"部分。身份源、角色、同受众拆库规则仍待 ADR-002 第 2 项确认，未接任何身份提供方。

## 已落地接口
- `auth.AccessScopeResolver`：应用层端口，返回当前请求的 `Optional<AccessScope>`。只允许由服务端认证上下文实现；无生产实现，容器中没有该 bean。
- `auth.AuthorizationDecision`：UNAUTHENTICATED / DENIED / PERMITTED。外企业与不存在的目标一律 DENIED，不可区分。
- `knowledgebase.KnowledgeBaseRef`：服务端持有的知识库身份（id + enterpriseId），不由客户端输入单独构造。
- `knowledgebase.KnowledgeBaseAuthorization`：所有知识库数据入口（查询、导出、历史、引用、原文读取）的统一门闸，参数为目标库与操作。参数里没有身份，身份只来自 resolver；resolver 返回 null 或空视为未认证。角色权限与知识库范围必须同时满足（PRD 第 3 章）。
- `auth.Role` / `auth.KnowledgeBaseAction` / `auth.RolePolicy`：按 PRD v0.3 第 3 章"建议映射"落地的角色矩阵，**待 D02 确认**。PRD 中"需显式内容管理/诊断授权"的格子尚无授权模型，一律拒绝；他人问答正文默认无人可读。
- HTTP：匿名访问业务路径 401 `UNAUTHENTICATED`，已认证无授权 403 `FORBIDDEN`，缺 CSRF 令牌 403。错误体固定为 `{code, message, requestId}` 并带 `X-Request-Id`，不含路径、主体或资源存在性。

## 已有测试（41 项通过）
- 单元：角色矩阵每格都有显式规则、员工不能管理/调试/读他人问答、显式授权格失败关闭、角色与库范围缺一即拒绝、无角色范围拒绝；无身份→未认证、resolver 返回 null 失败关闭、跨企业拒绝、同企业未授权库拒绝、外企业与未知库结果相同、空目标拒绝、构造参数非空。
- HTTP 冒烟：公开状态端点、匿名 GET/POST（含 CSRF）401、已认证 GET/POST 403、未知路径与业务路径响应一致。

## 待输入后补充的测试
1. 身份源接入：从真实认证上下文构造 `AccessScope` 的集成测试；伪造 `X-Enterprise-Id` 之类请求头或 DTO 字段必须被忽略。
2. 角色：D02 确认后按最终名称与职责修订矩阵；补显式内容管理授权与诊断授权模型及其测试；企业级操作（用户/角色/授权管理、模型凭据、审计查看）尚未建模。
3. 同受众拆库：同一企业两个库互不可见；一个用户被授予 A 不能读 B 的任何派生数据。
4. 撤权：授权删除后，检索、引用、原文、历史答案和继续对话在同一请求链内全部拒绝。
5. Widget：短期令牌签发、宿主来源允许列表、令牌过期后 401；不得使用长期 API Key。
6. CSRF/会话：根据选定方案（会话 Cookie 或 Bearer）决定 CSRF 策略并补对应测试。

## 未实现
登录、用户/角色存储、知识库持久化、任何业务路由。`KnowledgeBaseAuthorization` 尚无调用方。
