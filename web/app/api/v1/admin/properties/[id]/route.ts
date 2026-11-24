import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../../lib/server/authorization";
import {propertyDraft} from "../../../../../../lib/server/validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
const respond=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
type Props={params:Promise<{id:string}>};
const isUuid=(value:string)=>/^[0-9a-f]{8}-[0-9a-f-]{27,36}$/i.test(value);
export async function GET(request:Request,{params}:Props){
 const p=await resolveVerifiedPrincipal(request);if(!p)return respond({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:read_private"))return respond({error:"FORBIDDEN"},403);
 const {id}=await params;if(!isUuid(id))return respond({error:"INVALID_PROPERTY_ID"},400);
 try{
  const result=await withSubjectTransaction(p.subject,async db=>await db.query(
   "SELECT id,project_id,property_code,unit_number,asset_type,transaction_type,carpet_sqft,builtup_sqft,asking_rent_inr,asking_price_inr,furnishing,availability_status,verification_status,publication_status,version FROM tlc.properties WHERE id=$1",[id]));
  return result.rowCount===1?respond({item:result.rows[0]}):respond({error:"NOT_FOUND"},404);
 }catch{return respond({error:"SERVICE_UNAVAILABLE"},503);}
}
export async function PATCH(request:Request,{params}:Props){
 const p=await resolveVerifiedPrincipal(request);if(!p)return respond({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:edit"))return respond({error:"FORBIDDEN"},403);
 const {id}=await params;if(!isUuid(id))return respond({error:"INVALID_PROPERTY_ID"},400);
 let raw:unknown;try{raw=await request.json()}catch{return respond({error:"INVALID_JSON"},400)}
 const body=raw as Record<string,unknown>;const version=body?.expectedVersion;
 if(!Number.isSafeInteger(version)||Number(version)<1)return respond({error:"EXPECTED_VERSION_REQUIRED"},422);
 const parsed=propertyDraft(body);if(!parsed.value)return respond({error:parsed.error},422);
 const d=parsed.value;
 try{
  const item=await withSubjectTransaction(p.subject,async db=>{
   const result=await db.query<{id:string;version:number}>(
    "UPDATE tlc.properties SET project_id=$1,property_code=$2,unit_number=$3,asset_type=$4,transaction_type=$5,carpet_sqft=$6,builtup_sqft=$7,asking_rent_inr=$8,asking_price_inr=$9,furnishing=$10,version=version+1,verification_status='pending',publication_status='draft',updated_at=now() WHERE id=$11 AND version=$12 AND publication_status IN ('draft','pending_review','approved','unpublished') RETURNING id,version",
    [d.projectId,d.propertyCode,d.unitNumber,d.assetType,d.transactionType,d.carpetSqFt,d.builtupSqFt,d.askingRentInr,d.askingPriceInr,d.furnishing,id,version]);
   if(!result.rowCount)return null;
   const next=result.rows[0];
   await db.query("INSERT INTO tlc.audit_events(actor_account_id,action,entity_type,entity_id,previous_version,next_version,diff) VALUES($1,'edit','property',$2,$3,$4,$5::jsonb)",[p.accountId,id,version,next.version,JSON.stringify({changedKeys:["project_id","property_code","unit_number","asset_type","transaction_type","carpet_sqft","builtup_sqft","asking_rent_inr","asking_price_inr","furnishing"]})]);
   await db.query("INSERT INTO tlc.change_outbox(entity_type,entity_id,entity_version,operation) VALUES('property',$1,$2,'update')",[id,next.version]);
   return next;
  },true);
  return item?respond({item,published:false}):respond({error:"VERSION_CONFLICT_OR_NOT_EDITABLE"},409);
 }catch(e){return respond({error:(e as {code?:string}).code==="23505"?"DUPLICATE_PROPERTY_CODE":"SERVICE_UNAVAILABLE"},(e as {code?:string}).code==="23505"?409:503);}
}
export async function DELETE(){return respond({error:"PERMANENT_DELETION_DISABLED_USE_ARCHIVE_WORKFLOW"},405);}
