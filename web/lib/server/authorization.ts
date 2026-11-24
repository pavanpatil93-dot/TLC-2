import "server-only";
import {createRemoteJWKSet,jwtVerify} from "jose";
import {databasePool} from "./database";
import type {PoolClient} from "pg";
export type Permission="property:read_private"|"property:create"|"property:edit"|"property:archive"|"property:publish"|"project:manage"|"submission:create"|"submission:review"|"account:manage";
export type VerifiedPrincipal={accountId:string;subject:string;displayName:string;accountKind:string;approvalState:"active";permissions:ReadonlySet<Permission>};
const rolePermissions:Record<string,Permission[]>={
 admin:["property:read_private","property:create","property:edit","property:archive","property:publish","project:manage","submission:create","submission:review","account:manage"],
 commercial_manager:["property:read_private","property:create","property:edit","property:publish","project:manage","submission:create","submission:review"],
 residential_manager:["property:read_private","property:create","property:edit","submission:create","submission:review"],
 inventory_manager:["property:read_private","property:create","property:edit","submission:create","submission:review"],
 sales_executive:["property:read_private","property:create","submission:create"],
 read_only:["property:read_private"],
 owner:["submission:create"],developer:["submission:create"],broker:["submission:create"],client:[]
};
export function identityConfigured():boolean{return Boolean(process.env.TLC_AUTH_JWKS_URL&&process.env.TLC_AUTH_ISSUER&&process.env.TLC_AUTH_AUDIENCE&&process.env.TLC_DATABASE_URL);}
let jwks:ReturnType<typeof createRemoteJWKSet>|null=null;
async function verifySubject(request:Request):Promise<string|null>{
 if(!identityConfigured())return null;
 const header=request.headers.get("authorization")||"";
 const match=/^Bearer ([A-Za-z0-9_.-]+)$/.exec(header);
 if(!match)return null;
 try{
  const url=new URL(process.env.TLC_AUTH_JWKS_URL!);
  if(url.protocol!=="https:")return null;
  if(!jwks)jwks=createRemoteJWKSet(url,{timeoutDuration:3000,cooldownDuration:30000,cacheMaxAge:300000});
  const {payload}=await jwtVerify(match[1],jwks,{issuer:process.env.TLC_AUTH_ISSUER!,audience:process.env.TLC_AUTH_AUDIENCE!,clockTolerance:10});
  return typeof payload.sub==="string"&&payload.sub.length>5?payload.sub:null;
 }catch{return null;}
}
export async function withSubjectTransaction<T>(subject:string,operation:(db:PoolClient)=>Promise<T>,write=false):Promise<T>{
 const db=await databasePool().connect();
 try{
  await db.query(write?"BEGIN":"BEGIN READ ONLY");
  await db.query("SELECT set_config('tlc.auth_subject',$1,true)",[subject]);
  const value=await operation(db);
  await db.query("COMMIT");
  return value;
 }catch(e){await db.query("ROLLBACK").catch(()=>{});throw e;}
 finally{db.release();}
}
export async function resolveVerifiedPrincipal(request:Request):Promise<VerifiedPrincipal|null>{
 const subject=await verifySubject(request);
 if(!subject)return null;
 try{
  return await withSubjectTransaction(subject,async db=>{
   const a=await db.query<{id:string;display_name:string;account_kind:string;approval_state:string}>(
     "SELECT id, display_name, account_kind, approval_state FROM tlc.accounts WHERE auth_subject=$1 AND approval_state='active' LIMIT 1",[subject]);
   if(a.rowCount!==1)return null;
   const account=a.rows[0];
   const roles=await db.query<{role:string}>("SELECT role FROM tlc.account_roles WHERE account_id=$1",[account.id]);
   if(!roles.rowCount)return null;
   return {accountId:account.id,subject,displayName:account.display_name,accountKind:account.account_kind,approvalState:"active" as const,
    permissions:new Set(roles.rows.flatMap(x=>rolePermissions[x.role]||[]))};
  });
 }catch{return null;} // missing RLS policies, missing connection or account: deny
}
export function hasPermission(principal:VerifiedPrincipal|null,permission:Permission):boolean{return !!principal&&principal.permissions.has(permission);}
export const privateNoStore={"Cache-Control":"private, no-store, max-age=0","X-Content-Type-Options":"nosniff"} as const;
