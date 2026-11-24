"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import AccountRolePicker from "./account-role-picker";
type Me={authenticated?:boolean;status?:string;email?:string;active?:boolean;requestedAccountType?:string|null;
 account?:{displayName:string;kind:string;roles:string[];email:string|null;requestedAccountType?:string|null};permissions?:string[]};
const roleLabels:Record<string,string>={admin:"Administrator",commercial_manager:"Commercial Manager",residential_manager:"Residential Manager",inventory_manager:"Inventory Manager",sales_executive:"Sales Executive",read_only:"Read-Only Employee",owner:"Property Owner",developer:"Developer",broker:"Broker / Partner",client:"Client",tenant:"Tenant"};
export default function AccountDashboard(){
 const router=useRouter(),[profile,setProfile]=useState<Me|null>(null),[loading,setLoading]=useState(true);
 async function load(){
  try{
   let response=await fetch("/api/v1/auth/me",{cache:"no-store"});
   if(response.status===401){
    const refreshed=await fetch("/api/v1/auth/refresh",{method:"POST",cache:"no-store"});
    if(refreshed.ok)response=await fetch("/api/v1/auth/me",{cache:"no-store"});
   }
   const result=await response.json() as Me;
   setProfile(result);
  }catch{setProfile({status:"SERVICE_UNAVAILABLE"})}finally{setLoading(false)}
 }
 useEffect(()=>{void load()},[]);
 async function logout(){await fetch("/api/v1/auth/logout",{method:"POST"});router.replace("/login");router.refresh()}
 const active=profile?.status==="ACTIVE"&&profile.account;
 const roles=profile?.account?.roles||[];
 return <main className="account-page"><div className="container"><div className="account-heading"><div><span className="kicker">TLC ACCOUNT</span><h1>{active?"Welcome, "+profile!.account!.displayName:"Your account dashboard"}</h1><p>Secure personal workspace · Property management remains approval-controlled.</p></div>{profile?.authenticated?<button className="btn btn-outline" onClick={logout}>Sign out</button>:<Link className="btn" href="/login">Sign in →</Link>}</div>{loading?<div className="auth-info">Checking your account permissions…</div>:!active?<div className="auth-info"><strong>{profile?.status==="BACKEND_NOT_CONFIGURED"?"Account verification is pending backend setup":profile?.status==="ACCOUNT_PENDING_OR_UNAVAILABLE"?"Your account is awaiting approval":"Secure sign-in is required"}</strong><p>{profile?.status==="ACCOUNT_PENDING_OR_UNAVAILABLE"?"Your identity may be verified, but no active TLC account and role have been approved. Contact your TLC administrator.":profile?.status==="BACKEND_NOT_CONFIGURED"?"The staging database and role policies must be configured before this dashboard can grant access.":"Sign in using the email associated with your TLC account."}</p>{profile?.authenticated?<AccountRolePicker initialValue={profile?.requestedAccountType}/>:null}</div>:<><div className="account-summary"><div><small>ACCOUNT TYPE</small><strong>{roleLabels[profile!.account!.kind]||profile!.account!.kind}</strong></div><div><small>VERIFIED ROLES</small><strong>{roles.map(r=>roleLabels[r]||r).join(", ")||"None"}</strong></div><div><small>EMAIL</small><strong>{profile!.account!.email||"Verified account"}</strong></div></div><AccountRolePicker initialValue={profile?.account?.requestedAccountType||profile?.requestedAccountType}/><section className="account-section"><h2>Available workspace</h2><div className="account-actions"><Link href="/properties" className="account-action"><strong>Search properties →</strong><span>Browse approved commercial inventory</span></Link><Link href="/projects" className="account-action"><strong>Browse projects →</strong><span>Explore published projects and their units</span></Link><div className="account-action locked"><strong>Property management · Upcoming</strong><span>Employee editing, owner and broker posting are activated only after Batch 05–06 backend approval.</span></div><div className="account-action locked"><strong>Client enquiries · Upcoming</strong><span>Private leads and saved properties require the CRM integration batch.</span></div></div></section></>}</div></main>;
}
