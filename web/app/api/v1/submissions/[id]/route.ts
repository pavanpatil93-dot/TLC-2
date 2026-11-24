import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../lib/server/authorization";
import {safeText} from "../../../../../lib/server/validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
type Props={params:Promise<{id:string}>};
export async function PATCH(request:Request,{params}:Props){
 const reply=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"submission:review"))return reply({error:"FORBIDDEN"},403);
 const {id}=await params;
 if(!/^[0-9a-f]{8}-[0-9a-f-]{27,36}$/i.test(id))return reply({error:"INVALID_ID"},400);
 let input:unknown;try{input=await request.json()}catch{return reply({error:"INVALID_JSON"},400)}
 const data=input as {state?:unknown;reason?:unknown};
 const state=safeText(data?.state,30);const reason=safeText(data?.reason,500);
 if(!["under_review","returned","approved","rejected"].includes(state))return reply({error:"INVALID_REVIEW_STATE"},422);
 if(reason.length<6)return reply({error:"REVIEW_REASON_REQUIRED"},422);
 try{
  const item=await withSubjectTransaction(p.subject,async db=>{
   const row=await db.query<{id:string;state:string}>(
    "UPDATE tlc.property_submissions SET state=$1,reviewed_by=$2,reviewed_at=now(),review_reason=$3 WHERE id=$4 AND state IN('submitted','under_review','returned') RETURNING id,state",
    [state,p.accountId,reason,id]);
   if(!row.rowCount)return null;
   await db.query("INSERT INTO tlc.audit_events(actor_account_id,action,entity_type,entity_id,diff) VALUES($1,'review','property_submission',$2,$3::jsonb)",[p.accountId,id,JSON.stringify({state,reason})]);
   return row.rows[0];
  },true);
  return item?reply({item,published:false}):reply({error:"NOT_REVIEWABLE"},409);
 }catch{return reply({error:"REVIEW_SERVICE_NOT_ACTIVATED"},503);}
}
