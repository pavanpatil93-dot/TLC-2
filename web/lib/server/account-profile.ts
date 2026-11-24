import "server-only";
import {databaseConfigured,databasePool} from "./database";
export const ROLE_NAMES=["admin","commercial_manager","residential_manager","inventory_manager","sales_executive","read_only","owner","developer","broker","client","tenant"] as const;
export type AccountRole=typeof ROLE_NAMES[number];
export type AccountProfile={
 id:string;displayName:string;accountKind:string;roles:AccountRole[];
 approvalState:"active";email:string|null;
};
const VALID=new Set<string>(ROLE_NAMES);
export async function resolveAccountProfile(verifiedSubject:string):Promise<AccountProfile|null>{
 if(!databaseConfigured()||!/^[0-9a-f-]{36}$/i.test(verifiedSubject))return null;
 const client=await databasePool().connect();
 try{
  await client.query("BEGIN READ ONLY");
  await client.query("SELECT set_config('tlc.auth_subject',$1,true)",[verifiedSubject]);
  const r=await client.query<{
   id:string;display_name:string;account_kind:string;email:string|null;approval_state:string;role:string|null;
  }>(String.raw`SELECT a.id,a.display_name,a.account_kind,a.email,a.approval_state,r.role
     FROM tlc.accounts a LEFT JOIN tlc.account_roles r ON r.account_id=a.id
     WHERE a.auth_subject=$1 AND a.approval_state='active'`,[verifiedSubject]);
  await client.query("COMMIT");
  if(!r.rowCount)return null;
  const first=r.rows[0];
  const roles=Array.from(new Set(r.rows.map(x=>x.role).filter((x):x is AccountRole=>!!x&&VALID.has(x))));
  return {id:first.id,displayName:first.display_name,accountKind:first.account_kind,roles,approvalState:"active",email:first.email};
 }catch{
  try{await client.query("ROLLBACK")}catch{}
  return null; // fail closed on unavailable/misconfigured RLS policies
 }finally{client.release()}
}
