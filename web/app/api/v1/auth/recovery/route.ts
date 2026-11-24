import {NextResponse} from "next/server";
import {validMutationOrigin} from "../../../../../lib/server/auth-cookies";
export const runtime="nodejs";
export const dynamic="force-dynamic";
const headers={"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"};
export async function POST(req:Request){
 const reply=(ok:boolean,status=200)=>NextResponse.json({ok},{status,headers});
 if(!validMutationOrigin(req)){console.warn("TLC recovery: origin rejected");return reply(false,403);}
 const base=process.env.TLC_AUTH_URL?.trim(),key=process.env.TLC_AUTH_ANON_KEY?.trim();
 if(!base||!key)return reply(false,503);
 try{
 const u=new URL(base);if(u.protocol!=="https:"||u.username||u.password)return reply(false,503);
 const raw=await req.text();if(raw.length>16000)return reply(false,413);
 const b=JSON.parse(raw);
 if(b.action==="request"){
 const email=typeof b.email==="string"?b.email.trim():"";
 if(email.length>254||! /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))return reply(false,400);
 await fetch(u.origin+"/auth/v1/recover",{method:"POST",headers:{apikey:key,"Content-Type":"application/json"},body:JSON.stringify({email}),cache:"no-store",signal:AbortSignal.timeout(6500)});
 return reply(true);
 }
 if(b.action!=="reset"||typeof b.token!=="string"||b.token.length>8192||typeof b.password!=="string"||b.password.length<12||b.password.length>1024)return reply(false,400);
 const r=await fetch(u.origin+"/auth/v1/user",{method:"PUT",headers:{apikey:key,Authorization:"Bearer "+b.token,"Content-Type":"application/json"},body:JSON.stringify({password:b.password}),cache:"no-store",signal:AbortSignal.timeout(6500)});
 if(!r.ok){console.warn("TLC recovery: provider rejected password update",r.status);return reply(false,400);}
 await fetch(u.origin+"/auth/v1/logout?scope=global",{method:"POST",headers:{apikey:key,Authorization:"Bearer "+b.token},cache:"no-store",signal:AbortSignal.timeout(6500)}).catch(()=>{});
 return reply(true);
 }catch{console.warn("TLC recovery: request failed");return reply(false,400)}
}
