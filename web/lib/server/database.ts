import "server-only";
import {Pool} from "pg";
import type {PoolClient} from "pg";
/** Staging only until role policies and verified identities are provisioned.
 * No client-side imports. Never use a SUPERUSER or BYPASSRLS account.
 */
let pool:Pool|undefined;
export function databaseConfigured():boolean {return Boolean(process.env.TLC_DATABASE_URL);}
export async function assertRestrictedConnection(client:PoolClient):Promise<void>{
 const r=await client.query("SELECT current_user AS role, rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user");
 const role=r.rows[0];
 if(!role || role.role!=="tlc_web_runtime" || role.rolsuper || role.rolbypassrls)throw new Error("UNSAFE_DATABASE_ROLE");
}
export function databasePool():Pool {
 if(!databaseConfigured())throw new Error("DATABASE_NOT_CONFIGURED");
 if(!pool){
  pool=new Pool({connectionString:process.env.TLC_DATABASE_URL,
   max:5,connectionTimeoutMillis:2500,idleTimeoutMillis:10000,
   statement_timeout:4000,query_timeout:4500,
   application_name:"tlc2_web_staging"});
 }
 return pool;
}
export async function checkDatabaseConnectivity():Promise<boolean>{
 if(!databaseConfigured())return false;
 try {
  const client=await databasePool().connect();
  try {await assertRestrictedConnection(client);return true;} finally {client.release();}
 }catch(error){
  const e=error as {code?:unknown;message?:unknown};
  const code=typeof e?.code==='string' && /^[A-Z0-9_]{1,40}$/.test(e.code)?e.code:'UNKNOWN';
  const category=e?.message==='UNSAFE_DATABASE_ROLE'?'UNSAFE_DATABASE_ROLE':typeof e?.message==='string' && /timeout/i.test(e.message)?'TIMEOUT':'CONNECTION_OR_QUERY_FAILURE';
  console.warn('TLC database health failure',{code,category});
  return false;
 }
}
