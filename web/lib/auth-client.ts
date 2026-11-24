"use client";
import {createClient, type SupabaseClient} from "@supabase/supabase-js";
let instance:SupabaseClient|null=null;
export function clientAuthConfigured(){return Boolean(process.env.NEXT_PUBLIC_TLC_SUPABASE_URL&&process.env.NEXT_PUBLIC_TLC_SUPABASE_ANON_KEY);}
export function authClient():SupabaseClient|null{
 if(!clientAuthConfigured())return null;
 if(!instance)instance=createClient(process.env.NEXT_PUBLIC_TLC_SUPABASE_URL!,process.env.NEXT_PUBLIC_TLC_SUPABASE_ANON_KEY!,{auth:{autoRefreshToken:true,persistSession:true,detectSessionInUrl:false}});
 return instance;
}
export async function authenticatedFetch(url:string,options:RequestInit={}):Promise<Response>{
 const c=authClient();
 if(!c)throw new Error("IDENTITY_PROVIDER_NOT_CONFIGURED");
 const {data:{session},error}=await c.auth.getSession();
 if(error||!session?.access_token)throw new Error("LOGIN_REQUIRED");
 const headers=new Headers(options.headers);
 headers.set("Authorization","Bearer "+session.access_token);
 if(options.body&&!headers.has("Content-Type"))headers.set("Content-Type","application/json");
 return fetch(url,{...options,headers,cache:"no-store",credentials:"omit"});
}
