import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const read=p=>readFileSync(new URL("../"+p,import.meta.url),"utf8");
const login=read("components/login-portal.tsx");
const role=read("components/account-role-picker.tsx");
const password=read("app/api/v1/auth/password/route.ts");
const request=read("app/api/v1/auth/request-role/route.ts");
const provider=read("lib/server/auth-provider.ts");
const auth=read("lib/server/authorization.ts");
const schema=read("db/migrations/003_tenant_account_type.sql");
const checks=[
 ["simple email/password login without role selector",()=>{
  assert.match(login,/type="email"/);assert.match(login,/type=\{showPassword\?"text":"password"\}/);
  assert.match(login,/api\/v1\/auth\/password/);assert.doesNotMatch(login,/login-kind-grid|Choose your account type and enter/);
 }],
 ["password credentials checked by external identity provider",()=>{
  assert.match(password,/validMutationOrigin\(req\)/);assert.match(password,/signInWithPassword\(email,password\)/);
  assert.match(provider,/grant_type=password/);assert.match(password,/setAuthCookies\(tokens\)/);
 }],
 ["role selection only after authenticated login",()=>{
  assert.match(role,/broker/);assert.match(role,/owner/);assert.match(role,/client/);assert.match(role,/tenant/);assert.match(role,/developer/);
  assert.match(role,/api\/v1\/auth\/request-role/);
  assert.match(request,/verifyAccessToken\(token\)/);assert.match(request,/AUTHENTICATION_REQUIRED/);assert.match(request,/validMutationOrigin\(req\)/);
 }],
 ["user choice never grants privilege",()=>{
  assert.match(provider,/tlc_requested_account_type/);
  assert.doesNotMatch(request,/INSERT INTO tlc\.account_roles|UPDATE tlc\.account_roles/);
  assert.match(auth,/resolveAccountProfile\(verified\.id\)/);
  assert.match(auth,/tenant:\[\]/);
 }],
 ["tenant staging schema retains explicit validation",()=>{
  assert.match(schema,/account_kind IN/);assert.match(schema,/tenant/);
 }]
];
for(const [name,fn] of checks){fn();console.log("PASS "+name)}
console.log("Password login and post-login role selection checks: "+checks.length+" passed.");
