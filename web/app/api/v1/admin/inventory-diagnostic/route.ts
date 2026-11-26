import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore} from "../../../../../lib/server/authorization";
import {inventoryConnectivityDiagnostic} from "../../../../../lib/approved-sheets";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function GET(request:Request){
 const reply=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
 try{
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"account:manage")||!hasPermission(p,"property:publish"))return reply({error:"FORBIDDEN"},403);
 return reply(await inventoryConnectivityDiagnostic());
 }catch{return reply({error:"DIAGNOSTIC_UNAVAILABLE"},503);}
}
