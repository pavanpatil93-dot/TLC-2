"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import SiteHeader from "../../components/site-header";
import SiteFooter from "../../components/site-footer";
import {authenticatedFetch,authClient} from "../../lib/auth-client";
type Profile={account:{id:string;displayName:string;kind:string;approvalState:string};permissions:string[]};
export default function Dashboard(){
 const [profile,setProfile]=useState<Profile|null>(null),[state,setState]=useState("Checking your secure session…");
 useEffect(()=>{authenticatedFetch("/api/v1/auth/me").then(async r=>{if(r.status===401){setState("Sign in required.");return;}if(!r.ok){setState("Your account is not yet approved or private services are unavailable.");return;}const v:Profile=await r.json();setProfile(v);setState("");}).catch(()=>setState("Secure identity services are not configured."));},[]);
 async function logout(){await authClient()?.auth.signOut();window.location.assign("/login")}
 return <><SiteHeader/><main className="container workspace"><div className="workspace-head"><div><span className="kicker">TLC WORKSPACE</span><h1>{profile?"Welcome, "+profile.account.displayName:"My Workspace"}</h1><p>{profile?"Verified "+profile.account.kind+" account":"Your secure account workspace"}</p></div>{profile?<button type="button" className="btn btn-outline" onClick={()=>void logout()}>Sign out</button>:<Link className="btn" href="/login">Sign in</Link>}</div>{!profile?<div className="empty-note">{state}</div>:<div className="workspace-grid">{(profile.permissions.includes("property:read_private")?[{title:"Inventory Manager",text:"View submitted and internal inventory records",url:"/dashboard/properties"},{title:"Review Submissions",text:"Review incoming owner and broker listings",url:"/dashboard/submissions"},{title:"Website Enquiries",text:"Review website client requirements and calls",url:"/dashboard/enquiries"}]:[]).concat([{title:"Submit Property",text:"Submit a property for verification and approval",url:"/post-property"},{title:"Public Search",text:"Browse approved commercial property listings",url:"/properties"}]).map(x=><Link className="workspace-tile" key={x.title} href={x.url}><strong>{x.title} →</strong><p>{x.text}</p></Link>)}</div>}</main><SiteFooter/></>
}
