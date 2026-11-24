"use client";
import {useState} from "react";
import Link from "next/link";
import {authenticatedFetch} from "../../../lib/auth-client";
import type {PublicListing} from "../../../lib/public-inventory";
export default function PublicationReview(){
 const [tab,setTab]=useState("Commercial Inventory"),[id,setId]=useState(""),[listing,setListing]=useState<PublicListing|null>(null),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
 async function preview(){
 setBusy(true);setListing(null);setMessage("");
 try{const r=await authenticatedFetch("/api/v1/admin/publication-preview",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({key:tab+":"+id.trim()})});
 const data=await r.json();if(!r.ok){setMessage(r.status===403?"Administrator access required.":r.status===401?"Sign in required.":"Preview unavailable.");return;}
 setListing(data.listing);setMessage(data.listing?(data.configuredApproval?"Approval key configured; preview is not proof of live deployment.":"Eligible for review; not approved for publication."):"No eligible listing found. Check verification, availability, record status and required fields.");
 }catch{setMessage("Preview unavailable.");}finally{setBusy(false);}
 }
 return <main className="container workspace"><Link href="/dashboard/properties">Back to inventory</Link><h1>Website publication review</h1><p>Review the exact public listing fields. Publishing remains disabled until owner authority and release checks are complete.</p><form onSubmit={e=>{e.preventDefault();void preview();}}><label>Inventory tab <select value={tab} onChange={e=>{setTab(e.target.value);setListing(null);setMessage("");}}><option>Commercial Inventory</option><option>Residential Inventory</option></select></label><label>Property ID <input required value={id} onChange={e=>{setId(e.target.value);setListing(null);setMessage("");}}/></label><button className="btn" disabled={busy}>{busy?"Checking…":"Preview public listing"}</button></form><p role="status">{message}</p>{listing&&<section className="details-panel"><h2>{listing.title}</h2><p>{listing.location} · {listing.assetType} · {listing.transaction}</p><p>Carpet: {listing.areaSqFt} sq.ft. · ₹{listing.priceInr?.toLocaleString("en-IN")}</p><p>Built-up: {listing.builtUpSqFt??"Not supplied"} · {listing.furnishing||"Furnishing not supplied"}</p><details><summary>Exact public payload</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(listing,null,2)}</pre></details></section>}</main>;
}
