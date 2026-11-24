import {NextResponse} from "next/server";
import {readCookieHeader,ACCESS_COOKIE} from "../../../../../lib/server/auth-cookies";
import {verifyAccessToken,authConfigured} from "../../../../../lib/server/auth-provider";
import {resolveVerifiedPrincipal,privateNoStore} from "../../../../../lib/server/authorization";
import {databaseConfigured} from "../../../../../lib/server/database";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function GET(req:Request){
 if(!authConfigured())return NextResponse.json({authenticated:false,status:"NOT_CONFIGURED"},{status:503,headers:privateNoStore});
 const token=readCookieHeader(req,ACCESS_COOKIE);
 if(!token)return NextResponse.json({authenticated:false,status:"SIGN_IN_REQUIRED"},{status:401,headers:privateNoStore});
 const verified=await verifyAccessToken(token);
 if(!verified)return NextResponse.json({authenticated:false,status:"SESSION_EXPIRED"},{status:401,headers:privateNoStore});
 if(!databaseConfigured())return NextResponse.json({
  authenticated:true,status:"BACKEND_NOT_CONFIGURED",email:verified.email,active:false
 },{status:503,headers:privateNoStore});
 const principal=await resolveVerifiedPrincipal(req);
 if(!principal)return NextResponse.json({
  authenticated:true,status:"ACCOUNT_PENDING_OR_UNAVAILABLE",email:verified.email,active:false
 },{status:403,headers:privateNoStore});
 return NextResponse.json({
  authenticated:true,status:"ACTIVE",account:{displayName:principal.displayName,kind:principal.accountKind,roles:principal.roles,email:verified.email},
  permissions:Array.from(principal.permissions)
 },{headers:privateNoStore});
}
