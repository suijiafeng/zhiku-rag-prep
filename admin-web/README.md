# 知库管理端

React + TypeScript strict + Ant Design + Vite。打开后先进入登录页：企业 SSO 未接入（按钮禁用），本地开发用开发令牌登录，也可"以示例数据浏览"。登录并选中知识库后，总览、文档、问答、检索调试、评测、问答记录、用户与权限、系统设置等页面都使用真实接口；已登录但没有可访问的知识库时显示空态，不退回示例数据。令牌只保存在页面内存，刷新即失效。

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

## 目录

- src/App.tsx：导航与页面路由（未登录→示例页面，已登录→实时页面）
- src/api/：HTTP 客户端、SSE 问答流与请求代次
- src/features/：按页面划分的功能代码，`Live*.tsx` 为接真实接口的页面
- `*.test.tsx`：静态渲染与纯函数测试（node 环境）；`*.dom.test.tsx`：happy-dom + Testing Library 交互测试，文件首行 `// @vitest-environment happy-dom` 启用 DOM
- 编码、安全与验收约定以仓库根 AGENTS.md 与 docs/conventions.md 为准；接口以 contracts/openapi.yaml 为准

`validated: metadata-only` 只表示来源元数据检查通过，不能证明回答内容正确，界面也不得这样表述。

## 依赖基线

2026-10-14 通过官方 npm registry 核对：React/React DOM 19.3.0、Ant Design 6.6.6、Vite 8.3.4、@vitejs/plugin-react 6.1.2、Vitest 5.0.4、@types/react 与 @types/react-dom 19.3.0。TypeScript 固定经核对的 5.9.3 兼容基线，不追随新主版本。2026-10-15 新增仅测试用的 happy-dom 21.0.1 与 @testing-library/react 16.3.3（交互测试环境，不进入构建产物）。package.json 使用精确版本，package-lock.json 锁定依赖树。

查证：<https://registry.npmjs.org/react>、<https://registry.npmjs.org/antd>、<https://registry.npmjs.org/vite>、<https://registry.npmjs.org/typescript>。依赖版本核对不等同于安全审计；上线前另行审查依赖及生产鉴权。
