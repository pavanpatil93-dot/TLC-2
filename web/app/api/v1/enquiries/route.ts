import {NextResponse} from "next/server";
import {databasePool} from "../../../../lib/server/database";
import {safeText} from "../../../../lib/server/validation";
export const runtime="nodejs";export const dynamic="force-dynamic";
const headers={"Cache-Control":"private, no-store, max-age=0","X-Content-Type-Options":"nosniff"};
type Body={name?:unknown;phone?:unknown;propertyRef?:unknown;location?:unknown;requirement?:unknown;consent?:unknown;captchaToken?:unknown};
export async function POST(request:Request){
 const respond=(data:unknown,status=200)=>NextResponse.json(data,{status,headers});
 const secret=process.env.TLC_TURNSTILE_SECRET;
 if(!secret||!process.env.TLC_DATABASE_URL)return respond({error:"ENQUIRY_SERVICE_NOT_ACTIVATED"},503);
 const length=Number(request.headers.get("content-length")||"0");
 if(length>12000)return respond({error:"PAYLOAD_TOO_LARGE"},413);
 let raw:Body;try{raw=await request.json()}catch{return respond({error:"INVALID_JSON"},400)}
 const name=safeText(raw.name,120),phone=safeText(raw.phone,18),requirement=safeText(raw.requirement,2000);
 const propertyRef=safeText(raw.propertyRef,150),location=safeText(raw.location,160),captcha=safeText(raw.captchaToken,1000);
 if(name.length<2||!/^\+[1-9][0-9]{7,14}$/.test(phone)||requirement.length<5||raw.consent!==true||captcha.length<10)return respond({error:"VALIDATION_FAILED"},422);
 let verified=false;
 try{
  const body=new URLSearchParams({secret,response:captcha});
  const result=await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify",{method:"POST",body,signal:AbortSignal.timeout(5000)});
  const data=await result.json() as {success?:boolean;hostname?:string};
  // A verified token is insufficient if bound to a different host.
  const configuredHost=process.env.TLC_PUBLIC_HOSTNAME;
  verified=data.success===true&&(!configuredHost||data.hostname===configuredHost);
 }catch{return respond({error:"CAPTCHA_UNAVAILABLE"},503)}
 if(!verified)return respond({error:"CAPTCHA_FAILED"},403);
 const db=await databasePool().connect();
 try{
  await db.query("BEGIN");
  await db.query("SELECT set_config('tlc.public_enquiry_verified','true',true)");
  await db.query("INSERT INTO tlc.enquiries(contact_name,contact_phone_e164,property_public_ref,requested_location,requirement,consent_at) VALUES($1,$2,$3,$4,$5,now())",[name,phone,propertyRef||null,location||null,requirement]);
  await db.query("COMMIT");
  return respond({received:true,status:"PRIVATE_CRM_QUEUE"},201);
 }catch{await db.query("ROLLBACK").catch(()=>{});return respond({error:"ENQUIRY_UNAVAILABLE"},503);}
 finally{db.release();}
}
