import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";
export const dynamic="force-dynamic";
/** Fail-closed private property endpoint. Real authenticated writes must be added in later batches. */
async function requireScope(request:Request,scope:"property:read_private"|"property:create"){
 const principal=await resolveVerifiedPrincipal(request);
 if(!principal)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 if(!hasPermission(principal,scope))return NextResponse.json({error:"FORBIDDEN"},{status:403,headers:privateNoStore});
 return NextResponse.json({error:"NOT_ACTIVATED"},{status:503,headers:privateNoStore});
}
export async function GET(request:Request){return requireScope(request,"property:read_private");}
export async function POST(request:Request){return requireScope(request,"property:create");}
