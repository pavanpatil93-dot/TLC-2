import {NextResponse} from "next/server";
import {authConfigured} from "../../../../../lib/server/auth-provider";
import {databaseConfigured} from "../../../../../lib/server/database";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function GET(){
 return NextResponse.json({authConfigured:authConfigured(),databaseConfigured:databaseConfigured(),roleManagementEnabled:authConfigured()&&databaseConfigured()},{
  headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
 });
}
