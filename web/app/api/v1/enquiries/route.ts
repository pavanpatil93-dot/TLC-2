import {NextResponse} from "next/server";
export const runtime="nodejs";export const dynamic="force-dynamic";
/** Deliberately do not collect personal information until privacy, rate limit and CRM storage are ready. */
export async function POST(){
 return NextResponse.json({error:"ENQUIRY_SUBMISSION_NOT_ACTIVATED"},{status:503,headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
}
