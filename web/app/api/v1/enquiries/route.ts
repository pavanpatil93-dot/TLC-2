import {NextResponse} from "next/server";
import {databasePool} from "../../../../lib/server/database";
import {validateEnquiry} from "../../../../lib/enquiry-validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
const headers={"Cache-Control":"private, no-store, max-age=0","X-Content-Type-Options":"nosniff"};
type Body={name?:unknown;phone?:unknown;propertyRef?:unknown;location?:unknown;requirement?:unknown;consent?:unknown;captchaToken?:unknown};
export async function POST(request:Request){
 const respond=(data:unknown,status=200)=>NextResponse.json(data,{status,headers});
 const secret=process.env.TLC_TURNSTILE_SECRET;
 const configuredHost=process.env.TLC_PUBLIC_HOSTNAME;
 if(!secret||!process.env.TLC_DATABASE_URL||!configuredHost)return respond({error:"ENQUIRY_SERVICE_NOT_ACTIVATED"},503);
 const length=Number(request.headers.get("content-length")||"0");
 if(length>12000)return respond({error:"PAYLOAD_TOO_LARGE"},413);
 let raw:Body;try{const input:unknown=await request.json();if(!input||typeof input!=="object"||Array.isArray(input))throw new Error("INVALID_BODY");raw=input as Body;}catch{return respond({error:"INVALID_JSON"},400)}
 const validated=validateEnquiry(raw);
 if(!validated)return respond({error:"VALIDATION_FAILED"},422);
 const {name,phone,requirement,propertyRef,location,captcha}=validated;
 let verified=false;
 try{
  const body=new URLSearchParams({secret,response:captcha});
  const result=await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify",{method:"POST",body,signal:AbortSignal.timeout(5000)});
  const data=await result.json() as {success?:boolean;hostname?:string};
  // A verified token is insufficient if bound to a different host.
  verified=data.success===true&&data.hostname===configuredHost;
 }catch{return respond({error:"CAPTCHA_UNAVAILABLE"},503)}
 if(!verified)return respond({error:"CAPTCHA_FAILED"},403);
 const db=await databasePool().connect().catch(()=>null);
 if(!db)return respond({error:"ENQUIRY_UNAVAILABLE"},503);
 try{
  await db.query("BEGIN");
  await db.query("SELECT set_config('tlc.public_enquiry_verified','true',true)");
  await db.query("INSERT INTO tlc.property_enquiries(client_name,client_phone_e164,web_property_ref,web_location,web_requirement,web_consent_at,enquiry_kind,contact_consent,enquiry_state) VALUES($1,$2,$3,$4,$5,now(),'other',true,'new')",[name,phone,propertyRef||null,location||null,requirement]);
  await db.query("COMMIT");
  return respond({received:true,status:"PRIVATE_CRM_QUEUE"},201);
 }catch{await db.query("ROLLBACK").catch(()=>{});return respond({error:"ENQUIRY_UNAVAILABLE"},503);}
 finally{db.release();}
}
