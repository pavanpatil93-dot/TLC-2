import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore} from "../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function POST(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 if(!hasPermission(p,"property:create"))return NextResponse.json({error:"FORBIDDEN"},{status:403,headers:privateNoStore});
 return NextResponse.json({error:"SUBMISSION_BACKEND_NOT_ACTIVATED"},{status:503,headers:privateNoStore});
}
export async function GET(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 return NextResponse.json({error:"SUBMISSION_BACKEND_NOT_ACTIVATED"},{status:503,headers:privateNoStore});
}
