import {NextResponse} from "next/server";
import {authConfigured,sendLoginCode} from "../../../../../lib/server/auth-provider";
import {databaseConfigured} from "../../../../../lib/server/database";
import {validMutationOrigin} from "../../../../../lib/server/auth-cookies";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";
export const dynamic="force-dynamic";
const allowed=new Set(["employee","owner","developer","broker","client"]);
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 if(!authConfigured()||!databaseConfigured())return NextResponse.json({error:"SIGN_IN_NOT_CONFIGURED"},{status:503,headers:privateNoStore});
 const bytes=Number(req.headers.get("content-length")||"0");
 if(!Number.isFinite(bytes)||bytes>4096)return NextResponse.json({error:"REQUEST_TOO_LARGE"},{status:413,headers:privateNoStore});
 let payload:unknown;
 try{payload=await req.json()}catch{return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore})}
 if(!payload||typeof payload!=="object"||Array.isArray(payload))return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore});
 const input=payload as {email?:unknown;accountType?:unknown};
 const email=typeof input.email==="string"?input.email.trim().toLowerCase():"";
 if(email.length>254||!(/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)))return NextResponse.json({error:"INVALID_EMAIL"},{status:400,headers:privateNoStore});
 const requested=typeof input.accountType==="string"?input.accountType:"";
 if(!allowed.has(requested))return NextResponse.json({error:"INVALID_ACCOUNT_TYPE"},{status:400,headers:privateNoStore});
 // Employee identities must be invited/pre-provisioned. Other accounts may
 // authenticate, but NO app role is assigned until admin approval.
 const delivered=await sendLoginCode(email,requested!=="employee");
 return NextResponse.json(delivered?{status:"CHECK_EMAIL"}:{error:"REQUEST_UNAVAILABLE"},{
  status:delivered?200:503,headers:privateNoStore
 });
}
