# 验证记录 · 2026-10-14

## 前端（已完成）
环境：Node 24.19.0、npm 11.9.0。
- admin-web：npm ci；npm run check（typecheck + vitest + Vite build）通过。1 项静态渲染测试通过。Vite dev server 的首页及 App.tsx HTTP 冒烟通过。
- widget：npm ci；npm run check 通过。2 个文件共 9 项测试通过，包含 SSE 增量分帧、终止/上限和组件静态渲染；ESM/声明文件构建及构建包 Node import 通过。
- 管理端 bundle 提示：573.24 kB minified / 185.94 kB gzip，超过 Vite 默认 chunk 建议线。保留警告，未抬阈值；真实功能接入时再做按路由拆包。

## 后端
Java 21 原始 release 21 配置编译成功；最终 Maven clean verify：BUILD SUCCESS。
17 项测试全部通过：14 项权限/效力边界策略测试，3 项 HTTP 安全冒烟；Failures 0 / Errors 0 / Skipped 0。可执行 Spring Boot JAR 打包成功；实际启动 JAR，GET /api/status 返回 scaffold / modelEnabled=false 的 HTTP 冒烟通过，随后已停止进程。
实际命令（云端环境专用路径，不是用户安装要求）：
`/tmp/apache-maven-3.9.11/bin/mvn -U -s /tmp/zhiku-settings-final.xml -Dmaven.repo.local=/tmp/zhiku-m2 -Dmaven.compiler.fork=true -Dmaven.compiler.executable=/tmp/zhiku-jdk/bin/javac -B -f server/pom.xml clean verify`
临时 settings 仅设置环境网络代理，缓存和完整 JDK 位于临时目录。普通完整 JDK21/Maven3.9.11 环境使用 server/README 的 `mvn -B clean verify`。
首次遇默认缓存只读、裁剪 JRE 缺 ct.sym、下载/信任库环境问题；未改 release 21、未绕过 TLS。中间一次 14 通过 / 3 错误定位为 Mockito inline agent 自附着，加入标准 subclass mock maker 测试配置后全部通过；没有删测试/跳断言/放宽应用权限。Mockito 仍打印自附着警告，但本次测试结果全部通过。
官方工具来源：https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/ 及 https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse 。工具、缓存和环境设置不在交付包。

## 静态检查
Python yaml.safe_load 成功解析 compose.yaml、contracts/openapi.yaml、.github/workflows/ci.yml；这只是 YAML 语法检查，不是 OpenAPI 语义或 GitHub Actions 运行验证。

## 未验证及交付边界
- 本环境没有 Docker 命令，未启动 Postgres/pgvector、未执行 migration、未做数据库集成测试。
- 没有真实浏览器视觉/E2E、前后端业务联调、真实认证或模型调用；没有真实企业资料。
- CI 配置已提供，未在 GitHub 运行。未做生产部署、压力测试、安全审计或RAG质量评测。
- 未创建远程仓库、推送、部署、付费或开启持久服务。完整产品任务仍为 pending。

## 本机复现 · 2026-10-14（macOS）
环境：Java 21.0.12.1、Maven 3.9.16、Node 24.12.0、npm 11.7.0。
- server：`mvn -B clean verify` BUILD SUCCESS，17 项测试全部通过，可执行 JAR 打包成功。Mockito 仍打印自附着警告。
- widget：`npm ci && npm run check` 通过，9 项测试通过，ESM/声明文件构建成功。
- admin-web：`npm ci && npm run check` 通过，1 项测试通过，Vite 构建成功；573.24 kB chunk 警告保留。
- 环境问题：用户 npm 缓存中存在 root 属主目录导致 `npm ci` 报 EACCES，改用 `npm ci --cache <临时目录>` 绕开，未修改系统权限，与仓库无关。
- 仍未验证：Docker 未启动 Postgres/pgvector，migration 与数据库集成测试未执行；CI 未在 GitHub 运行。

## 剩余功能实现 · 2026-10-14（macOS，本机）
环境：Java 21.0.12.1、Maven 3.9.16、Node 24.12.0、Docker 29.8.2、Ollama（qwen3.5 对话模型；本机 Ollama 未开启 embedding 接口）。
- server：`mvn -B clean verify` BUILD SUCCESS，98 项测试通过，其中集成测试用 Testcontainers 启动 pgvector:0.8.0-pg16。
- admin-web：`npm run check` 通过，64 项测试。widget：`npm run check` 通过，26 项测试，构建包 Node 导入成功。
- 端到端（本机 compose Postgres + spring-boot + Vite，浏览器操作）：Flyway 在空库执行 V1–V6；开发令牌连接后列出授权库；篡改签名 401；页面新建文档与版本、批准、发布；API 上传 .md 返回 202，.pdf 返回 422；真实 worker 切块后因未配置 embedding 将版本标记失败且不重试，页面显示原因；问答页经 SSE 收到 DEPENDENCY_UNAVAILABLE 与请求编号，未保存会话；刷新后令牌消失。
- 联调中发现并修复：无内容版本被显示为"解析中"并无限轮询。
- 未验证：真实 embedding 模型下的检索与问答（本机无可用 embedding 接口）；真实模型回答质量与引用语义；多实例并发 worker；CI 在 GitHub 运行；生产 IdP、性能与安全审计。

## 设计稿其余页面 · 2026-10-14（本机）
- server：`mvn -B clean verify` 110 项通过（新增管理员授权查询、/api/me、角色矩阵、反馈与问答记录、挂件令牌提交反馈）。Flyway V7 在已有数据的本地库上升级成功。
- admin-web：`npm run check` 84 项通过；widget：31 项通过。
- 浏览器联调：用户与权限页授权与撤销（成员数 2→3→2，审计记录 KB_GRANT/KB_REVOKE）；问答记录页显示本人正文、隐藏他人正文；本人可评价、评价他人 403；登录页无效令牌提示、有效令牌进入、断开回到登录；API 与集成页按所选知识库生成代码；挂件静态预览桌面 380×620、手机全屏无横向滚动；问答失败显示错误态与重试。
- 联调用的两条问答记录是写入本机开发库的虚构数据，不在仓库中。
- 未验证：挂件在真实宿主中的跨域调用与反馈提交；撤销确认弹框的浏览器点击；真实 embedding 与模型下的完整问答。

## 总览统计与检索评测 · 2026-10-14（本机）
- server：`mvn -B clean verify` 121 项通过；Flyway V8 在已有数据的本地库上升级成功。
- admin-web：92 项通过，构建成功。
- 联调：总览页显示真实计数（文档、切块、向量 0、今日问答、7 日柱状图、好评率 n/N），他人提问正文不可见；评测用例摘录不在原文时 422，在原文时定位到字符区间 [49, 60) 并可批准；运行评测因本机未配置 embedding 返回 503，页面显示错误态与请求编号，未写入运行结果。
- 未验证：配置 embedding 后的真实检索评测数值；回答正确性与引用语义的人工评测（尚未实现）。
