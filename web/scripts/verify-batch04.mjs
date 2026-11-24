import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const files={
 auth:read("lib/server/authorization.ts"),
 provider:read("lib/server/auth-provider.ts"),
 cookies:read("lib/server/auth-cookies.ts"),
 admin:read("app/api/v1/admin/properties/route.ts"),
 me:read("app/api/v1/auth/me/route.ts"),
 otp:read("app/api/v1/auth/request-code/route.ts"),
 verify:read("app/api/v1/auth/verify-code/route.ts"),
 policy:read("db/migrations/002_auth_read.sql"),
 schema:read("db/migrations/001_core.sql"),
 env:read(".env.example")
};
const checks=[
 ["server-verified identity",()=>{assert.match(files.auth,/verifyAccessToken\(token\)/);assert.match(files.auth,/resolveAccountProfile\(verified\.id\)/)}],
 ["no header role spoofing",()=>{assert.doesNotMatch(files.auth,/headers\.get\(["']x-role/i);assert.doesNotMatch(files.auth,/headers\.get\(["']x-user/i)}],
 ["unauthenticated private API denied",()=>{assert.match(files.admin,/AUTHENTICATION_REQUIRED/);assert.match(files.admin,/status:401/);assert.match(files.admin,/NOT_ACTIVATED/)}],
 ["secure browser session cookies",()=>{assert.match(files.cookies,/httpOnly:true/);assert.match(files.cookies,/sameSite:"lax"/);assert.match(files.cookies,/validMutationOrigin/)}],
 ["OTP provider verification and origin check",()=>{assert.match(files.otp,/validMutationOrigin\(req\)/);assert.match(files.verify,/validMutationOrigin\(req\)/);assert.match(files.provider,/\/auth\/v1\/user/)}],
 ["permissions remain server controlled",()=>{assert.match(files.me,/resolveVerifiedPrincipal\(req\)/);assert.match(files.auth,/hasPermission/)}],
 ["RLS self-profile policies",()=>{assert.match(files.policy,/CREATE POLICY tlc_self_profile_read/);assert.match(files.policy,/CREATE POLICY tlc_self_roles_read/);assert.match(files.schema,/FORCE ROW LEVEL SECURITY/)}],
 ["no public database/auth environment names",()=>{assert.doesNotMatch(files.env,/NEXT_PUBLIC_TLC_AUTH/);assert.doesNotMatch(files.env,/NEXT_PUBLIC_TLC_DATABASE/)}]
];
for(const [name,fn] of checks){fn();console.log("PASS "+name)}
console.log("Batch 04 static security checks passed: "+checks.length);
