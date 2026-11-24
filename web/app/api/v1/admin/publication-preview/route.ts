import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore} from "../../../../../lib/server/authorization";
import {readPublicationPreview} from "../../../../../lib/approved-sheets";
import {approvedPublicationKeys} from "../../../../../lib/public-inventory";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export async function POST(request:Request){
 const reply=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"account:manage")||!hasPermission(p,"property:publish"))return reply({error:"FORBIDDEN"},403);
 let body:unknown;try{body=await request.json()}catch{return reply({error:"INVALID_JSON"},400)}
 const key=(body as {key?:unknown})?.key;
 if(typeof key!=="string"||! /^(Commercial Inventory|Residential Inventory):[A-Za-z0-9_-]{1,64}$/.test(key))return reply({error:"INVALID_PROPERTY_KEY"},422);
 try{
 const listings=await readPublicationPreview(new Set([key]));
 return reply({listing:listings[0]??null,configuredApproval:approvedPublicationKeys(process.env.TLC_PUBLIC_APPROVED_IDS).has(key),publicationChanged:false});
 }catch{return reply({error:"SERVICE_UNAVAILABLE"},503);}
}
