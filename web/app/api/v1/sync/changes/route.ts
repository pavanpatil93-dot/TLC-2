import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function GET(request:Request){
 const reply=(data:unknown,status=200)=>NextResponse.json(data,{status,headers:privateNoStore});
 const p=await resolveVerifiedPrincipal(request);if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:read_private"))return reply({error:"FORBIDDEN"},403);
 const raw=new URL(request.url).searchParams.get("after")||"0";
 if(!/^\d{1,15}$/.test(raw))return reply({error:"INVALID_CURSOR"},400);
 try{
  const result=await withSubjectTransaction(p.subject,async db=>(await db.query(
   "SELECT id,entity_type,entity_id,entity_version,operation,created_at FROM tlc.change_outbox WHERE id>$1 AND entity_type='property' ORDER BY id ASC LIMIT 200",[raw])).rows);
  return reply({changes:result,nextCursor:result.length?String(result[result.length-1].id):raw,writeSyncEnabled:false});
 }catch{return reply({error:"SYNC_NOT_ACTIVATED"},503);}
}
export async function POST(){
 return NextResponse.json({error:"ANDROID_UPLOAD_REQUIRES_IDEMPOTENCY_AND_DEVICE_CONFLICT_TESTS"},{status:503,headers:privateNoStore});
}
