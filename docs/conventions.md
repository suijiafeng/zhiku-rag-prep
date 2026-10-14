# 开发约定

## 后端
Java 21；包 dev.zhiku.<业务模块>；类 UpperCamelCase、字段 lowerCamelCase。DTO 使用 record + Bean Validation；避免实体直接出网。Controller 只做协议适配，事务在 application；构造器注入，禁止字段注入。UTC Instant 存时间，业务时区显式配置，生效区间 [from, until)。外部调用必须超时、上限、取消、降级和脱敏审计；此包无外部调用。
认证接入前不开放业务接口；所有查询、导出、调试、历史、引用与文件读取走相同 ACL。服务端取 enterpriseId/userId，不从请求 DTO 接受权威身份。错误不泄露隐藏资源是否存在。

## 前端和 Widget
TypeScript strict；组件 UpperCamelCase，hooks useX；按 feature 放代码，API 放独立适配层。不渲染原始 HTML；Markdown 若加入须采用允许列表 sanitizer。明确 loading/empty/error/forbidden/cancelled，不能把 mock 标成生产数据。Widget 样式隔离、独立构建，运行时获取短期令牌，不存长期凭据；宿主来源允许列表和短期令牌签发尚待设计。

## API / SSE
先改 contracts/openapi.yaml，再改双端类型/测试。HTTP 错误采用 code/message/requestId（正文不包含文档、SQL、凭据或堆栈）；401 未认证，403 无权，409 冲突，422 参数不满足，429 限流，503 依赖不可用。尚未实现统一错误处理器。
草案 POST + fetch SSE：draft → validated → done；error 为终止失败。仅收到 done 且之前有 validated 才能视为完整；validated 中 validation=metadata-only，UI 显示“来源信息检查通过，请核对内容”。中断/错误丢弃可用答案状态，不能自动重试 POST 造成重复计费。需要请求 ID、撤权检测、取消与错误测试后才能上线。

## SQL / 测试 / 依赖
迁移 `V<number>__description.sql`，已发布 migration 不改，新增前向版本；禁止生产自动 clean/drop 或 ORM ddl-auto=update。必须验证空库迁移、升级、权限隔离和数据回滚方案；目前仅有未自动运行的扩展 migration。
单测检验边界和失败，集成测真实 Postgres/pgvector，端到端覆盖撤权/断流/效力切换；mock 无法证明真实召回质量。评测真值用文档/版本/页码或字符区间，与 chunk_id 解耦。
固定直接依赖和 lockfile；更新说明原因、兼容性和安全检查，禁止随意 latest。CI 不使用真实密钥、不发送企业资料。每次变更最少执行对应包 README 的检查；CI 必须真实失败。
