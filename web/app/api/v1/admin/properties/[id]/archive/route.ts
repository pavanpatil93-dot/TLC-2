import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
type Props={params:Promise<{id:string}>};
export async function POST(request:Request,{params}:Props){
 const p=await resolveVerifiedPrincipal(request);
 const reply=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
 if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:archive"))return reply({error:"FORBIDDEN"},403);
 const {id}=await params;
 if(!/^[0-9a-f]{8}-[0-9a-f-]{27,36}$/i.test(id))return reply({error:"INVALID_ID"},400);
 let body:unknown;try{body=await request.json()}catch{return reply({error:"INVALID_JSON"},400)}
 const expectedVersion=(body as {expectedVersion?:unknown})?.expectedVersion;
 if(!Number.isSafeInteger(expectedVersion)||Number(expectedVersion)<1)return reply({error:"EXPECTED_VERSION_REQUIRED"},422);
 // No published record may be archived until a separately reviewed unpublish workflow is active.
 try{
  const item=await withSubjectTransaction(p.subject,async db=>{
   const x=await db.query<{version:number}>("UPDATE tlc.properties SET publication_status='archived',archived_at=now(),version=version+1,updated_at=now() WHERE id=$1 AND version=$2 AND publication_status IN ('draft','pending_review','approved','unpublished') RETURNING version",[id,expectedVersion]);
   if(!x.rowCount)return null;
   await db.query("INSERT INTO tlc.audit_events(actor_account_id,action,entity_type,entity_id,previous_version,next_version) VALUES($1,'archive','property',$2,$3,$4)",[p.accountId,id,expectedVersion,x.rows[0].version]);
   await db.query("INSERT INTO tlc.change_outbox(entity_type,entity_id,entity_version,operation) VALUES('property',$1,$2,'archive')",[id,x.rows[0].version]);
   return x.rows[0];
  },true);
  return item?reply({item,archived:true}):reply({error:"CONFLICT_OR_PUBLICATION_REQUIRES_SEPARATE_UNPUBLISH"},409);
 }catch{return reply({error:"SERVICE_UNAVAILABLE"},503);}
}
