import {NextResponse} from "next/server";
import {saveRequestedAccountType,verifyAccessToken} from "../../../../../lib/server/auth-provider";
import {ACCESS_COOKIE,readCookieHeader,validMutationOrigin} from "../../../../../lib/server/auth-cookies";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";
export const dynamic="force-dynamic";
const allowed=new Set(["broker","owner","client","tenant","developer"]);
/** Stores a requested account category in untrusted user metadata.
 * This never creates a tlc.accounts record or grants account_roles privileges.
 */
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 const token=readCookieHeader(req,ACCESS_COOKIE);
 if(!token||!await verifyAccessToken(token))return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 const length=Number(req.headers.get("content-length")||"0");
 if(!Number.isFinite(length)||length>1024)return NextResponse.json({error:"INVALID_REQUEST"},{status:413,headers:privateNoStore});
 let body:unknown;
 try{body=await req.json()}catch{return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore})}
 if(!body||typeof body!=="object"||Array.isArray(body))return NextResponse.json({error:"INVALID_REQUEST"},{status:400,headers:privateNoStore});
 const kind=(body as {accountType?:unknown}).accountType;
 if(typeof kind!=="string"||!allowed.has(kind))return NextResponse.json({error:"INVALID_ACCOUNT_TYPE"},{status:400,headers:privateNoStore});
 const saved=await saveRequestedAccountType(token,kind);
 return NextResponse.json(saved?{status:"REQUEST_SAVED",accountType:kind,approvalRequired:true}:{error:"REQUEST_UNAVAILABLE"},{
  status:saved?200:503,headers:privateNoStore
 });
}
