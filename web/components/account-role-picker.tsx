"use client";
import {useEffect,useState} from "react";
const types=[{value:"broker",label:"Broker",description:"List opportunities and work with client requirements"},{value:"owner",label:"Owner",description:"Track your property listings"},{value:"client",label:"Client",description:"Find and shortlist properties"},{value:"tenant",label:"Tenant",description:"Search lease options and manage requirements"},{value:"developer",label:"Developer",description:"Submit and manage project requests"}] as const;
export type RequestedType=typeof types[number]["value"];
export default function AccountRolePicker({initialValue}:{initialValue?:string|null}){
 const [type,setType]=useState<RequestedType|null>(null);
 const [saving,setSaving]=useState(false);
 const [message,setMessage]=useState("");
 useEffect(()=>{setType(types.find(t=>t.value===initialValue)?.value||null)},[initialValue]);
 async function save(){
  if(!type||saving)return;
  setSaving(true);setMessage("");
  try{
   const r=await fetch("/api/v1/auth/request-role",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({accountType:type}),cache:"no-store"});
   const response=await r.json() as {status?:string;error?:string};
   if(!r.ok)throw Error(response.error==="AUTHENTICATION_REQUIRED"?"Session expired. Please sign in again.":"Unable to save your selection. Please try again.");
   setMessage("Preference saved: "+types.find(t=>t.value===type)?.label+". TLC will review your account access. This does not grant property publishing or staff permissions.");
  }catch(e){setMessage(e instanceof Error?e.message:"Unable to save preference.")}finally{setSaving(false)}
 }
 return <section className="account-section account-choice-section"><div className="account-choice-heading"><div><span className="kicker">YOUR TLC PROFILE</span><h2>How will you use TLC?</h2><p>Choose your account type after login. You can update your preference later.</p></div></div><div className="account-type-grid">{types.map(item=><button type="button" key={item.value} className={"account-type-option"+(type===item.value?" selected":"")} aria-pressed={type===item.value} onClick={()=>{setType(item.value);setMessage("");}}><strong>{item.label}</strong><span>{item.description}</span></button>)}</div><button type="button" className="btn account-choice-save" disabled={!type||saving} onClick={save}>{saving?"Saving…":"Save account type →"}</button>{message?<p role="status" className="auth-feedback">{message}</p>:null}<p className="account-choice-note">Account type is a preference, not an approved security role. TLC staff must approve any restricted permissions separately.</p></section>
}
