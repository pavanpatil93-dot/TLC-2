import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function GET(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 return NextResponse.json({account:{id:p.accountId,displayName:p.displayName,kind:p.accountKind,approvalState:p.approvalState},permissions:[...p.permissions]},{headers:privateNoStore});
}
