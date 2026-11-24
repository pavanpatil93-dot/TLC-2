import "server-only";
/**
 * Supabase Auth (GoTrue) server-to-server email OTP adapter.
 * Uses only server-side environment variables. User input never grants a TLC role.
 */
const allowedHosts=new Set(["https:"]);
function config(){
 const url=process.env.TLC_AUTH_URL?.trim();
 const key=process.env.TLC_AUTH_ANON_KEY?.trim();
 if(!url||!key)return null;
 try{
  const u=new URL(url);
  if(!allowedHosts.has(u.protocol)||u.username||u.password||u.search||u.hash)return null;
  return {base:u.origin,key};
 }catch{return null;}
}
export function authConfigured(){return config()!==null;}
function timeout(){return AbortSignal.timeout(6500)}
export async function sendLoginCode(email:string,createUser:boolean):Promise<boolean>{
 const c=config();if(!c)return false;
 try{
  const r=await fetch(c.base+"/auth/v1/otp",{
   method:"POST",headers:{"apikey":c.key,"Content-Type":"application/json"},
   body:JSON.stringify({email,create_user:createUser}),
   cache:"no-store",signal:timeout()
  });return r.ok;
 }catch{return false;}
}
export type AuthTokens={access_token:string;refresh_token:string;expires_in:number;token_type:string};
function tokens(v:unknown):AuthTokens|null{
 if(!v||typeof v!=="object")return null;
 const x=v as Record<string,unknown>;
 if(typeof x.access_token!=="string"||typeof x.refresh_token!=="string"||typeof x.expires_in!=="number"||x.token_type!=="bearer")return null;
 if(!x.access_token||!x.refresh_token||!Number.isFinite(x.expires_in))return null;
 return {access_token:x.access_token,refresh_token:x.refresh_token,expires_in:x.expires_in,token_type:"bearer"};
}
export async function verifyLoginCode(email:string,code:string):Promise<AuthTokens|null>{
 const c=config();if(!c)return null;
 try{
  const r=await fetch(c.base+"/auth/v1/verify",{
   method:"POST",headers:{"apikey":c.key,"Content-Type":"application/json"},
   body:JSON.stringify({email,token:code,type:"email"}),cache:"no-store",signal:timeout()
  });
  return r.ok?tokens(await r.json()):null;
 }catch{return null;}
}
export async function rotateTokens(refreshToken:string):Promise<AuthTokens|null>{
 const c=config();if(!c)return null;
 try{
  const r=await fetch(c.base+"/auth/v1/token?grant_type=refresh_token",{
   method:"POST",headers:{"apikey":c.key,"Content-Type":"application/json"},
   body:JSON.stringify({refresh_token:refreshToken}),cache:"no-store",signal:timeout()
  });
  return r.ok?tokens(await r.json()):null;
 }catch{return null;}
}
export async function verifyAccessToken(token:string):Promise<{id:string;email:string|null}|null>{
 const c=config();if(!c||!token||token.length>8192)return null;
 try{
  const r=await fetch(c.base+"/auth/v1/user",{
   headers:{"apikey":c.key,"Authorization":"Bearer "+token},
   cache:"no-store",signal:timeout()
  });
  if(!r.ok)return null;
  const v=await r.json() as {id?:unknown;email?:unknown};
  if(typeof v.id!=="string"||!/^[0-9a-f-]{36}$/i.test(v.id))return null;
  return {id:v.id,email:typeof v.email==="string"?v.email:null};
 }catch{return null;}
}
