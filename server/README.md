# Server
前置 Java 21、Maven 3.9.11+、Docker（集成测试用 Testcontainers 启动 pgvector）。

```sh
mvn -B clean verify
```

## 本地运行
1. 仓库根目录复制 `.env.example` 为 `.env`，填入新的数据库密码和 `ZHIKU_JWT_SECRET`（`openssl rand -base64 48`）。
2. `docker compose up -d postgres`；首次启动时 Flyway 自动执行 `db/migration`（只做前向迁移，clean 已禁用）。
3. `cd server && mvn spring-boot:run`，服务读取 `../.env`。
4. 可选：`docker compose exec -T postgres psql -U zhiku_local -d zhiku < scripts/dev-seed.sql` 写入虚构示例知识库与授权。
5. 签发开发令牌：`node scripts/dev-token.mjs --user 00000000-0000-4000-8000-0000000000a1 --enterprise 00000000-0000-4000-8000-0000000000e1 --roles KB_OPERATOR,EMPLOYEE`。

`/api/status` 公开；其余 `/api/**` 需要 Bearer JWT，非 `/api` 路径一律拒绝。身份只取自已验证令牌，授权库每请求从数据库读取。本地签发脚本只用于开发，生产应接企业 IdP。没有默认用户、默认密码或后门。

测试使用 Mockito subclass mock maker，避免容器/JDK 中 inline agent 自附着限制。测试 JWT 密钥每次运行随机生成，不入库。
