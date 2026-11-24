import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
/** No unauthenticated sync cursor or private changes. Device identity is NOT an auth credential. */
export async function GET(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 return NextResponse.json({error:"SYNC_NOT_ACTIVATED"},{status:503,headers:privateNoStore});
}
export async function POST(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 return NextResponse.json({error:"SYNC_NOT_ACTIVATED"},{status:503,headers:privateNoStore});
}
