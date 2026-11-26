import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,withSubjectTransaction,privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function GET(request:Request){
 const reply=(data:unknown,status=200)=>NextResponse.json(data,{status,headers:privateNoStore});
 const p=await resolveVerifiedPrincipal(request);if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"property:read_private"))return reply({error:"FORBIDDEN"},403);
 if(!["admin","employee"].includes(p.accountKind))return reply({error:"FORBIDDEN"},403);
 try{
  const rows=await withSubjectTransaction(p.subject,async db=>(await db.query(
   "SELECT id,client_name AS contact_name,client_phone_e164 AS contact_phone_e164,web_property_ref AS property_public_ref,web_location AS requested_location,web_requirement AS requirement,enquiry_state AS status,created_at FROM tlc.property_enquiries WHERE web_consent_at IS NOT NULL ORDER BY created_at DESC LIMIT 100")).rows);
  return reply({items:rows});
 }catch{return reply({error:"ENQUIRIES_UNAVAILABLE"},503);}
}
