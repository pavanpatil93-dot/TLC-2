import "server-only";
import {cookies} from "next/headers";
import type {AuthTokens} from "./auth-provider";
export const ACCESS_COOKIE="tlc_auth_access";
export const REFRESH_COOKIE="tlc_auth_refresh";
const base={httpOnly:true,secure:process.env.NODE_ENV==="production",sameSite:"lax" as const,path:"/"};
export async function setAuthCookies(tokens:AuthTokens){
 const c=await cookies();
 c.set(ACCESS_COOKIE,tokens.access_token,{...base,maxAge:Math.min(Math.max(Math.floor(tokens.expires_in),30),3600)});
 c.set(REFRESH_COOKIE,tokens.refresh_token,{...base,maxAge:60*60*24*14});
}
export async function clearAuthCookies(){
 const c=await cookies();c.delete(ACCESS_COOKIE);c.delete(REFRESH_COOKIE);
}
export function readCookieHeader(request:Request,name:string):string|null{
 const raw=request.headers.get("cookie")||"";
 for(const part of raw.split(";")){
  const i=part.indexOf("=");
  if(i<0||part.slice(0,i).trim()!==name)continue;
  try{return decodeURIComponent(part.slice(i+1).trim())}catch{return null}
 }
 return null;
}
/** Same-site origin check for every state-changing browser request. */
export function validMutationOrigin(request:Request):boolean{
 const origin=request.headers.get("origin");if(!origin)return false;
 try{
  const url=new URL(request.url), o=new URL(origin);
  if(o.origin===url.origin)return true;
  // Vercel may reconstruct Request.url using an internal hostname.
  // Trust only platform-provided deployment hostnames, never forwarded headers.
  if(process.env.VERCEL==="1"){
   for(const host of [process.env.VERCEL_URL,process.env.VERCEL_BRANCH_URL]){
    if(host && o.origin==="https://"+host)return true;
   }
  }
  return false;
 }catch{return false;}
}
