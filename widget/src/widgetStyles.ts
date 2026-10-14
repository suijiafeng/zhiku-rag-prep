/** Every selector is prefixed with .zhiku- so nothing leaks into the host page. */
export const WIDGET_CSS = `
.zhiku-root{position:fixed;right:24px;bottom:24px;z-index:2147483000;font:14px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;color:#1d2433}
.zhiku-launcher{width:52px;height:52px;border-radius:50%;border:0;background:var(--zhiku-primary);color:#fff;font-size:22px;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.18)}
.zhiku-panel{width:380px;height:620px;max-height:calc(100vh - 48px);background:#fff;border-radius:14px;box-shadow:0 12px 40px rgba(0,0,0,.18);display:flex;flex-direction:column;overflow:hidden}
.zhiku-header{display:flex;align-items:center;gap:10px;padding:14px 16px;background:var(--zhiku-primary);color:#fff}
.zhiku-header h2{margin:0;font-size:15px;font-weight:600}
.zhiku-header p{margin:0;font-size:11px;opacity:.8}
.zhiku-logo{width:30px;height:30px;border-radius:8px;background:#fff;color:var(--zhiku-primary);display:grid;place-items:center;font-weight:700}
.zhiku-close{margin-left:auto;border:0;background:transparent;color:#fff;font-size:20px;cursor:pointer}
.zhiku-body{flex:1;overflow-y:auto;padding:14px;display:flex;flex-direction:column;gap:10px;background:#f7f8fb}
.zhiku-bot{align-self:flex-start;max-width:88%;background:#fff;border:1px solid #e6e9ef;border-radius:12px;padding:10px 12px;white-space:pre-wrap}
.zhiku-user{align-self:flex-end;max-width:80%;background:#e8eefb;border-radius:12px;padding:8px 12px;white-space:pre-wrap}
.zhiku-quick{display:flex;flex-wrap:wrap;gap:8px}
.zhiku-chip{border:0;border-radius:999px;background:#e8eefb;color:var(--zhiku-primary);padding:4px 12px;font:inherit;font-size:12px;cursor:pointer}
.zhiku-cite{border:0;background:none;color:var(--zhiku-primary);padding:0 1px;font:inherit;cursor:pointer}
.zhiku-cite[aria-expanded="true"]{text-decoration:underline}
.zhiku-source{margin-top:8px;background:#f1f3f7;border-radius:8px;padding:8px 10px;font-size:12px;color:#3a4256}
.zhiku-source strong{display:block;color:var(--zhiku-primary);font-weight:600}
.zhiku-note{margin:6px 0 0;font-size:11px;color:#8b93a7}
.zhiku-error{color:#c0392b}
.zhiku-feedback{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-top:8px;font-size:12px;color:#6b7385}
.zhiku-feedback button{border:1px solid #d7dce5;background:#fff;border-radius:6px;padding:2px 10px;font:inherit;font-size:12px;cursor:pointer}
.zhiku-feedback button[aria-pressed="true"]{border-color:var(--zhiku-primary);color:var(--zhiku-primary)}
.zhiku-form{display:flex;gap:8px;padding:10px 12px;border-top:1px solid #eef0f4;background:#fff}
.zhiku-form textarea{flex:1;resize:none;border:1px solid #d7dce5;border-radius:8px;padding:6px 8px;font:inherit}
.zhiku-send,.zhiku-stop{border:0;border-radius:8px;background:var(--zhiku-primary);color:#fff;padding:0 16px;font:inherit;cursor:pointer}
.zhiku-send:disabled{opacity:.5;cursor:not-allowed}
.zhiku-stop{background:#1d2433;padding:4px 12px;align-self:flex-start}
@media (max-width:480px){.zhiku-root{right:0;bottom:0}.zhiku-panel{width:100vw;height:100vh;max-height:100vh;border-radius:0}.zhiku-root .zhiku-launcher{margin:0 16px 16px 0}}
`;
