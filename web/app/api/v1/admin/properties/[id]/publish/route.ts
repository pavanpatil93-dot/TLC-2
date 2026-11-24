import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore} from "../../../../../../../lib/server/authorization";
export const runtime="nodejs";
export async function POST(request:Request){
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return NextResponse.json({error:"AUTHENTICATION_REQUIRED"},{status:401,headers:privateNoStore});
 if(!hasPermission(p,"property:publish"))return NextResponse.json({error:"FORBIDDEN"},{status:403,headers:privateNoStore});
 // Deliberate legal/consent gate: RLS and verified staff alone do NOT prove public-listing authority.
 return NextResponse.json({error:"PUBLICATION_REQUIRES_OWNER_AUTHORITY_EVIDENCE_AND_RELEASE_GATE"},{status:503,headers:privateNoStore});
}
