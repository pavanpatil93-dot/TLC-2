import "server-only";
import {Pool} from "pg";
/** Staging only until role policies and verified identities are provisioned.
 * No client-side imports. Never use a SUPERUSER or BYPASSRLS account.
 */
let pool:Pool|undefined;
export function databaseConfigured():boolean {return Boolean(process.env.TLC_DATABASE_URL);}
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
  const result=await databasePool().query("SELECT 1 AS ok");
  return result.rows[0]?.ok===1;
 }catch{return false;}
}
