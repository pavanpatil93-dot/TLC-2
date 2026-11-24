import {NextResponse} from "next/server";
import {readCookieHeader,REFRESH_COOKIE,setAuthCookies,clearAuthCookies,validMutationOrigin} from "../../../../../lib/server/auth-cookies";
import {rotateTokens,authConfigured} from "../../../../../lib/server/auth-provider";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 if(!authConfigured())return NextResponse.json({error:"SIGN_IN_NOT_CONFIGURED"},{status:503,headers:privateNoStore});
 const refresh=readCookieHeader(req,REFRESH_COOKIE);
 if(!refresh)return NextResponse.json({error:"SIGN_IN_REQUIRED"},{status:401,headers:privateNoStore});
 const tokens=await rotateTokens(refresh);
 if(!tokens){await clearAuthCookies();return NextResponse.json({error:"SESSION_EXPIRED"},{status:401,headers:privateNoStore})}
 await setAuthCookies(tokens);
 return NextResponse.json({status:"SESSION_REFRESHED"},{headers:privateNoStore});
}
