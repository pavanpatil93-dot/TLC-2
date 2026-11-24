"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import SiteHeader from "../../../components/site-header";import SiteFooter from "../../../components/site-footer";
import {authenticatedFetch} from "../../../lib/auth-client";
type Enquiry={id:string;contact_name:string;contact_phone_e164:string;property_public_ref:string|null;requested_location:string|null;requirement:string;status:string;created_at:string};
export default function Enquiries(){const [items,setItems]=useState<Enquiry[]>([]),[state,setState]=useState("Loading private CRM enquiries…");
 useEffect(()=>{authenticatedFetch("/api/v1/admin/enquiries").then(async r=>{const x=await r.json();if(r.ok){setItems(x.items||[]);setState("");}else setState(x.error||"Private CRM unavailable");}).catch(()=>setState("Secure CRM service not configured."));},[]);
 return <><SiteHeader/><main className="container workspace"><div className="workspace-head"><div><span className="kicker">INTERNAL CRM</span><h1>Website Enquiries</h1><p>New requests from the public website. Client details are visible only to authorized employees.</p></div><Link href="/dashboard" className="btn btn-outline">Workspace</Link></div>{state?<div className="empty-note">{state}</div>:items.length?<div className="workspace-table">{items.map(x=><div key={x.id} className="workspace-row"><div><strong>{x.contact_name} · {x.requested_location||"Area not given"}</strong><p>{x.requirement}</p><small>{x.property_public_ref||"General requirement"} · {x.status}</small></div><a className="btn btn-outline" href={"tel:"+x.contact_phone_e164}>Call</a></div>)}</div>:<div className="empty-note">No private website enquiries received.</div>}</main><SiteFooter/></>
}
