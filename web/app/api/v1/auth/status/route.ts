import {NextResponse} from "next/server";
import {authConfigured} from "../../../../../lib/server/auth-provider";
import {databaseConfigured,checkDatabaseConnectivity} from "../../../../../lib/server/database";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function GET(){
 const databaseConnected=await checkDatabaseConnectivity();
 return NextResponse.json({databaseConnected,authConfigured:authConfigured(),databaseConfigured:databaseConfigured(),roleManagementEnabled:authConfigured()&&databaseConnected},{
  headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}
 });
}
