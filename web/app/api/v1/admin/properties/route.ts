import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../lib/server/authorization";
import {propertyDraft} from "../../../../../lib/server/validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
const resp=(data:unknown,status=200)=>NextResponse.json(data,{status,headers:privateNoStore});
export async function GET(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return resp({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:read_private"))return resp({error:"FORBIDDEN"},403);
 try{
  const items=await withSubjectTransaction(p.subject,async db=>(await db.query(
   "SELECT id,property_code,unit_number,asset_type,transaction_type,carpet_sqft,builtup_sqft,asking_rent_inr,asking_price_inr,publication_status,availability_status,verification_status,version,updated_at FROM tlc.properties ORDER BY updated_at DESC LIMIT 100")).rows);
  return resp({items});
 }catch{return resp({error:"INVENTORY_NOT_ACTIVATED"},503);}
}
export async function POST(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return resp({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:create"))return resp({error:"FORBIDDEN"},403);
 let body:unknown;try{body=await request.json()}catch{return resp({error:"INVALID_JSON"},400)}
 const draft=propertyDraft(body);if(!draft.value)return resp({error:draft.error},422);
 const d=draft.value;
 try{
  const created=await withSubjectTransaction(p.subject,async db=>{
   const insert=await db.query<{id:string;property_code:string;version:number}>(
    "INSERT INTO tlc.properties(project_id,property_code,unit_number,business_segment,asset_type,transaction_type,carpet_sqft,builtup_sqft,asking_rent_inr,asking_price_inr,furnishing,source_account_id,publication_status,availability_status,verification_status) VALUES($1,$2,$3,'commercial',$4,$5,$6,$7,$8,$9,$10,$11,'draft','not_confirmed','pending') RETURNING id,property_code,version",
    [d.projectId,d.propertyCode,d.unitNumber,d.assetType,d.transactionType,d.carpetSqFt,d.builtupSqFt,d.askingRentInr,d.askingPriceInr,d.furnishing,p.accountId]);
   const item=insert.rows[0];
   await db.query("INSERT INTO tlc.audit_events(actor_account_id,action,entity_type,entity_id,next_version,diff) VALUES($1,'create','property',$2,$3,'{}'::jsonb)",[p.accountId,item.id,item.version]);
   await db.query("INSERT INTO tlc.change_outbox(entity_type,entity_id,entity_version,operation) VALUES('property',$1,$2,'create')",[item.id,item.version]);
   return item;
  },true);
  return resp({item:created,published:false},201);
 }catch(e){return resp({error:(e as {code?:string}).code==="23505"?"DUPLICATE_PROPERTY_CODE":"CREATE_NOT_ACTIVATED"},(e as {code?:string}).code==="23505"?409:503);}
}
