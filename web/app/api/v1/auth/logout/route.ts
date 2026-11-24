import {NextResponse} from "next/server";
import {clearAuthCookies,validMutationOrigin} from "../../../../../lib/server/auth-cookies";
import {privateNoStore} from "../../../../../lib/server/authorization";
export const runtime="nodejs";export const dynamic="force-dynamic";
export async function POST(req:Request){
 if(!validMutationOrigin(req))return NextResponse.json({error:"BAD_ORIGIN"},{status:403,headers:privateNoStore});
 await clearAuthCookies();
 return NextResponse.json({status:"SIGNED_OUT"},{headers:privateNoStore});
}
