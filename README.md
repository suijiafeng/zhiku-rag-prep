# 知库 RAG · Vibe Coding 准备包
基于产品需求文档 v0.3。先用规则约束小步开发，再实现产品。

## 从这里开始
1. 给编码助手读 `AGENTS.md`（全仓库规范唯一入口）。
2. 读 `docs/architecture.md` 和 `docs/conventions.md`；在 `docs/adr/002-open-decisions.md` 确认阻塞项。
3. 从 `TASKS.md` 只选一个任务，套用 `docs/ai-task-template.md`，明确路径和验收。

## 目录
- server：Java 21 / Spring Boot 最小应用、默认拒绝业务路径、边界策略单测
- admin-web：React + TypeScript + antd 静态冒烟页
- widget：独立 React Widget 壳与 SSE 草案解析器
- contracts/openapi.yaml：状态端点与未来 SSE 数据形状（未实现聊天）
- compose.yaml：可选本地 Postgres/pgvector；无生产部署
- docs：架构、约定、ADR、任务提示词、验证记录

## 运行与验证
后端：`cd server && mvn -B clean verify`；启动 `mvn spring-boot:run`，访问 http://127.0.0.1:8080/api/status 。需 Java 21 / Maven 3.9.11。
前端与 Widget 见各包 README，使用提交的 lockfile 执行 npm ci 后检查/构建。
可选数据库：复制 `.env.example` 为 `.env`，填入新本地密码，再 `docker compose up -d postgres`；没有程序读取数据库、没有自动迁移。勿把本地容器配置用于生产。销毁卷会删数据，不在默认步骤中执行。

## 范围与真实状态
没有实现登录、文档上传、业务持久化、任务队列、真实向量检索、模型问答、引用验证或评测平台。纯 Java 策略示例和协议解析测试不等同于端到端 RAG 验收。目录规划不代表功能完成。
无真实企业材料、无模型 API Key、无外部网关。所有测试资料是中性虚构 fixture。
实际执行证据与未验证项见 `docs/verification.md`。此包没有推送、部署或创建远程仓库。
