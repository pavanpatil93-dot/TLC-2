import {NextResponse} from "next/server";
import {authConfigured,signInWithPassword} from "../../../../../lib/server/auth-provider";
import {validMutationOrigin,setAuthCookies} from "../../../../../lib/server/auth-cookies";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 if(!authConfigured())return NextResponse.json({error:"SIGN_IN_NOT_CONFIGURED"},{status:503,headers:privateNoStore});
 const length=Number(req.headers.get("content-length")||"0");
 if(!Number.isFinite(length)||length>4096)return NextResponse.json({error:"INVALID_REQUEST"},{status:413,headers:privateNoStore});
 let body:unknown;
 try{body=await req.json()}catch{return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore})}
 if(!body||typeof body!=="object"||Array.isArray(body))return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore});
 const input=body as {email?:unknown;password?:unknown};
 const email=typeof input.email==="string"?input.email.trim().toLowerCase():"";
 const password=typeof input.password==="string"?input.password:"";
 if(!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)||email.length>254||password.length<1||password.length>1024){
  return NextResponse.json({error:"INVALID_CREDENTIALS"},{status:401,headers:privateNoStore});
 }
 const tokens=await signInWithPassword(email,password);
 if(!tokens)return NextResponse.json({error:"INVALID_CREDENTIALS"},{status:401,headers:privateNoStore});
 await setAuthCookies(tokens);
 return NextResponse.json({status:"SIGNED_IN",next:"/dashboard"},{headers:privateNoStore});
}
