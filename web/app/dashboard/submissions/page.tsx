"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import SiteHeader from "../../../components/site-header";
import SiteFooter from "../../../components/site-footer";
import {authenticatedFetch} from "../../../lib/auth-client";
type Submission={id:string;state:string;created_at:string;proposed_values:Record<string,unknown>};
export default function Submissions(){
 const [items,setItems]=useState<Submission[]>([]),[status,setStatus]=useState("Loading secure submissions…");
 useEffect(()=>{authenticatedFetch("/api/v1/submissions").then(async r=>{const x=await r.json();if(r.ok){setItems(x.items||[]);setStatus("");}else setStatus(r.status===401?"Please sign in":r.status===403?"You lack review access":"Submission service not activated");}).catch(()=>setStatus("Secure service unavailable."));},[]);
 return <><SiteHeader/><main className="container workspace"><div className="workspace-head"><div><span className="kicker">INTERNAL REVIEW</span><h1>Property Submissions</h1><p>New owner, broker and employee listings remain private until manager approval.</p></div><Link href="/dashboard" className="btn btn-outline">Workspace</Link></div>{status?<div className="empty-note">{status}</div>:items.length?<div className="workspace-table">{items.map(x=><div className="workspace-row" key={x.id}><div><strong>{String(x.proposed_values?.projectName||"New submission")}</strong><p>Received {new Date(x.created_at).toLocaleDateString("en-IN")} · {x.state}</p></div><span className="status">Pending verification</span></div>)}</div>:<div className="empty-note">No submissions in the current queue.</div>}</main><SiteFooter/></>
}
