import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../lib/server/authorization";
import {submissionDraft} from "../../../../lib/server/validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
const response=(data:unknown,status=200)=>NextResponse.json(data,{status,headers:privateNoStore});
export async function GET(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return response({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"submission:create")&&!hasPermission(p,"submission:review"))return response({error:"FORBIDDEN"},403);
 try{
  const items=await withSubjectTransaction(p.subject,async db=>(await db.query("SELECT id,state,created_at,proposed_values FROM tlc.property_submissions ORDER BY created_at DESC LIMIT 100")).rows);
  return response({items});
 }catch{return response({error:"NOT_ACTIVATED"},503);}
}
export async function POST(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return response({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"submission:create"))return response({error:"FORBIDDEN"},403);
 let body:unknown;try{body=await request.json()}catch{return response({error:"INVALID_JSON"},400)}
 const parsed=submissionDraft(body);if(!parsed.value)return response({error:parsed.error},422);
 try{
  const item=await withSubjectTransaction(p.subject,async db=>{
   const r=await db.query<{id:string;state:string}>("INSERT INTO tlc.property_submissions(submitted_by,proposed_values,state) VALUES($1,$2::jsonb,'submitted') RETURNING id,state",[p.accountId,JSON.stringify(parsed.value)]);
   await db.query("INSERT INTO tlc.audit_events(actor_account_id,action,entity_type,entity_id,diff) VALUES($1,'submit','property_submission',$2,'{}'::jsonb)",[p.accountId,r.rows[0].id]);
   return r.rows[0];
  },true);
  return response({item,published:false},201);
 }catch{return response({error:"SUBMISSION_SERVICE_NOT_ACTIVATED"},503);}
}
