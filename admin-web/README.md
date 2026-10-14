# 知库管理端骨架

React + TypeScript strict + Ant Design + Vite 管理端。未连接时显示标注的示例数据；点击"连接本地服务"粘贴开发令牌后，文档管理、问答工作台、检索调试使用真实接口，系统设置读取 /api/status。令牌只保存在页面内存，刷新即失效。总览与评测中心仍为示例数据。

## 本地运行

要求 Node.js >= 22.12（建议 Node 24 LTS）及 npm。独立安装，不依赖 widget。

```sh
cd admin-web
npm ci
npm run dev
```

打开 Vite 输出的本地地址（默认 http://127.0.0.1:5173）。开发服务器把 /api 代理到 127.0.0.1:8080，服务端无需 CORS。不启动服务端时页面以示例模式运行。

```sh
npm run typecheck
npm test
npm run build
npm run preview
# 聚合验收
npm run check
```

## 目录与后续接入点

- src/App.tsx：静态工程状态及边界说明
- src/main.tsx：React 入口
- src/App.test.tsx：服务端静态渲染 smoke test，不是浏览器交互测试
- 仓库根 AGENTS.md 与 docs/conventions.md：唯一编码、安全与验收约定

KnowledgeBase、Document/Version、IngestionJob、Query/Citations 只标记为待实现。后续接入以根目录 API 契约为准；当前服务端只实现 GET /api/status，没有聊天路由。`validated: metadata-only` 不能证明回答内容正确。

## 依赖基线

2026-10-14 通过官方 npm registry 核对：React/React DOM 19.3.0、Ant Design 6.6.6、Vite 8.3.4、@vitejs/plugin-react 6.1.2、Vitest 5.0.4、@types/react 与 @types/react-dom 19.3.0。TypeScript 固定经核对的 5.9.3 兼容基线，不追随新主版本。package.json 使用精确版本，package-lock.json 锁定依赖树。

查证：<https://registry.npmjs.org/react>、<https://registry.npmjs.org/antd>、<https://registry.npmjs.org/vite>、<https://registry.npmjs.org/typescript>。依赖版本核对不等同于安全审计；上线前另行审查依赖及生产鉴权。
