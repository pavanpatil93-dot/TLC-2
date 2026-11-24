import {NextResponse} from "next/server";
import {authConfigured,verifyLoginCode} from "../../../../../lib/server/auth-provider";
import {setAuthCookies,validMutationOrigin} from "../../../../../lib/server/auth-cookies";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 if(!authConfigured())return NextResponse.json({error:"SIGN_IN_NOT_CONFIGURED"},{status:503,headers:privateNoStore});
 const bytes=Number(req.headers.get("content-length")||"0");
 if(!Number.isFinite(bytes)||bytes>4096)return NextResponse.json({error:"REQUEST_TOO_LARGE"},{status:413,headers:privateNoStore});
 let payload:unknown;
 try{payload=await req.json()}catch{return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore})}
 if(!payload||typeof payload!=="object"||Array.isArray(payload))return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore});
 const input=payload as {email?:unknown;code?:unknown};
 const email=typeof input.email==="string"?input.email.trim().toLowerCase():"";
 const code=typeof input.code==="string"?input.code.trim():"";
 if(email.length>254||!(/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))||!/^[0-9]{6,8}$/.test(code)){
  return NextResponse.json({error:"INVALID_CODE_OR_EMAIL"},{status:400,headers:privateNoStore});
 }
 const tokens=await verifyLoginCode(email,code);
 if(!tokens)return NextResponse.json({error:"INVALID_OR_EXPIRED_CODE"},{status:401,headers:privateNoStore});
 await setAuthCookies(tokens);
 return NextResponse.json({status:"SESSION_VERIFIED"},{headers:privateNoStore});
}
