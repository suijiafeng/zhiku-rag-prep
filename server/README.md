# Server
前置 Java 21、Maven 3.9.11（官方发行版）。

```sh
mvn -B clean verify
mvn spring-boot:run
curl http://127.0.0.1:8080/api/status
```
唯一业务外可公开路径是无敏感状态 `/api/status`。其余默认拒绝；没有默认用户密码、JWT secret、demo 登录后门。模型未连接。DB migration 尚未自动加载；未引入 JDBC/Flyway 是为了不把待决 schema 假装接通。

测试使用 Mockito subclass mock maker，避免容器/JDK 中 inline agent 自附着限制。本骨架不需要 final/static mocking；没有禁用测试、断言或安全配置。未来若需要 inline mocking，应按官方文档配置启动时 javaagent 并重新验证，不能临时放宽系统 attach 安全设置。
