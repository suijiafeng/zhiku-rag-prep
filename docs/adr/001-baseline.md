# ADR-001：准备阶段基线
状态：架构建议已落成最小壳；业务/安全设计待确认。日期 2026-10-14，PRD v0.3。
选择模块化单体、Java 21、Spring Boot 3.5.15 + Spring AI BOM 1.1.8，React/TypeScript/antd 管理端和独立 Widget。未引入 AI starter，只锁 BOM，不会调用模型。
官方 1.1.8 发布说明明确升级到 Boot 3.5.15；Maven Central 2026-10-14 检查 1.1 系列最新为 1.1.8。选择兼容已发布组合，不追跨主版本 latest；不等于承诺无漏洞或商业支持期限。上线前重新审查支持周期/安全公告。
来源：https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/
https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml
Postgres 16 + pgvector 0.8.0 镜像为固定本地开发基线，尚未在本环境启动/安全评估；上线前应更新经验证补丁并固定 digest。
不采用 ES/Kafka/K8s/微服务，原因是准备阶段没有复杂度收益证据。后续重大变更新增 ADR，经确认后实施。
