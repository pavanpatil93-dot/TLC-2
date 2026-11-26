"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import SiteHeader from "../../../components/site-header";
import SiteFooter from "../../../components/site-footer";
import {authenticatedFetch} from "../../../lib/auth-client";
type P={id:string;property_code:string|null;unit_number:string|null;asset_type:string;transaction_type:string;publication_status:string;verification_status:string;availability_status:string;version:number};
export default function InventoryManager(){
 const [rows,setRows]=useState<P[]>([]),[message,setMessage]=useState("Loading secure inventory…"),[query,setQuery]=useState("");
 useEffect(()=>{authenticatedFetch("/api/v1/admin/properties").then(async r=>{const data=await r.json();if(r.ok){setRows(data.items||[]);setMessage("");}else setMessage(r.status===401?"Sign in required":r.status===403?"You don't have inventory access":"Inventory service unavailable until database and authorization are activated");}).catch(()=>setMessage("Secure backend unavailable."));},[]);
 const filtered=rows.filter(x=>[x.property_code,x.unit_number,x.asset_type,x.transaction_type].join(" ").toLowerCase().includes(query.toLowerCase()));
 return <><SiteHeader/><main className="container workspace"><div className="workspace-head"><div><span className="kicker">INTERNAL WORKSPACE</span><h1>Inventory Manager</h1><p>Add, review and manage commercial property records.</p></div><Link href="/dashboard/properties/new" className="btn">+ Add Property</Link></div><div className="workspace-toolbar"><input aria-label="Search inventory" placeholder="Search property code, unit, type" value={query} onChange={e=>setQuery(e.target.value)}/><Link className="btn btn-outline" href="/dashboard/publication">Website publication review</Link><Link className="btn btn-outline" href="/dashboard">Dashboard</Link></div>{message?<div className="empty-note">{message}</div>:filtered.length?<div className="workspace-table">{filtered.map(x=><div className="workspace-row" key={x.id}><div><strong>{x.property_code||"No code"} · {x.unit_number||"No unit"}</strong><p>{x.asset_type} · {x.transaction_type} · {x.availability_status}</p></div><div><small>{x.verification_status} · {x.publication_status}</small><Link className="btn btn-outline" href={"/dashboard/properties/"+x.id}>View / Edit</Link></div></div>)}</div>:<div className="empty-note">No records found in your authorized inventory.</div>}</main><SiteFooter/></>
}
