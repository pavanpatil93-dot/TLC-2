import {NextResponse} from "next/server";
export const runtime="nodejs";export const dynamic="force-dynamic";
/** Public activation indicators; never include environment values or credentials. */
export async function GET(){
 return NextResponse.json({login:false,ownerPosting:false,brokerPosting:false,employeeWrite:false,androidSync:false,crmSubmission:false,publication:"approval_only",environment:"staging_preview"},{headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
}
