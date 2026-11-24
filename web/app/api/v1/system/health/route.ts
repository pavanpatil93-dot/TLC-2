import {NextResponse} from "next/server";
import {checkDatabaseConnectivity,databaseConfigured} from "../../../../lib/server/database";
export const runtime="nodejs";
export const dynamic="force-dynamic";
/** Non-sensitive staging readiness: no secrets, IDs, data or SQL errors exposed. */
export async function GET(){
 const configured=databaseConfigured();
 const healthy=configured?await checkDatabaseConnectivity():false;
 return NextResponse.json({
  service:"tlc2-web",
  database:!configured?"not_configured":healthy?"reachable":"unavailable",
  inventoryWritesEnabled:false,
  authenticatedAccountsEnabled:false
 },{status:configured&&!healthy?503:200,headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
}
