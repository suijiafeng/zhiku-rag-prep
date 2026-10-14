#!/usr/bin/env node
// Issues a short-lived HS256 token for LOCAL development only. Reads ZHIKU_JWT_SECRET from the
// environment or ./.env. Never use this for production identities; real deployments use an IdP.
// Usage: node scripts/dev-token.mjs --user <uuid> --enterprise <uuid> --roles EMPLOYEE,KB_OPERATOR [--minutes 60]
import { createHmac } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => (arg.startsWith("--") ? [...pairs, [arg.slice(2), all[i + 1]]] : pairs), []),
);
const env = existsSync(".env")
  ? Object.fromEntries(readFileSync(".env", "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]))
  : {};
const secret = process.env.ZHIKU_JWT_SECRET ?? env.ZHIKU_JWT_SECRET;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

if (!secret || Buffer.byteLength(secret) < 32) {
  console.error("ZHIKU_JWT_SECRET must be set (>= 32 bytes) in the environment or .env");
  process.exit(1);
}
if (!uuid.test(args.user ?? "") || !uuid.test(args.enterprise ?? "")) {
  console.error("--user and --enterprise must be UUIDs");
  process.exit(1);
}

const minutes = Math.min(Number(args.minutes ?? 60), 480);
const now = Math.floor(Date.now() / 1000);
const b64 = (v) => Buffer.from(typeof v === "string" ? v : JSON.stringify(v)).toString("base64url");
const header = b64({ alg: "HS256", typ: "JWT" });
const payload = b64({
  iss: "zhiku-local",
  aud: ["zhiku-api"],
  sub: args.user,
  enterprise_id: args.enterprise,
  roles: (args.roles ?? "EMPLOYEE").split(",").filter(Boolean),
  iat: now,
  exp: now + minutes * 60,
});
const signature = createHmac("sha256", secret).update(`${header}.${payload}`).digest("base64url");
console.log(`${header}.${payload}.${signature}`);
