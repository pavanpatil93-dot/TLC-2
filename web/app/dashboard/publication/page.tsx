"use client";
import {useState} from "react";
import Link from "next/link";
import {authenticatedFetch} from "../../../lib/auth-client";
import type {PublicListing} from "../../../lib/public-inventory";
export default function PublicationReview(){
 const [tab,setTab]=useState("Commercial Inventory"),[id,setId]=useState(""),[listing,setListing]=useState<PublicListing|null>(null),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
 const [review,setReview]=useState<{key:string;digest:string|null;version:number;enabled:boolean;revoked:boolean;expiry:string|null}|null>(null);
 const [evidence,setEvidence]=useState(""),[expiry,setExpiry]=useState(""),[confirmed,setConfirmed]=useState(false);
 async function change(action:"approve"|"revoke"){
 if(!review||review.key!==tab+":"+id.trim())return;
 setBusy(true);
 try{
 const r=await authenticatedFetch("/api/v1/admin/publication-approval",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({key:review.key,action,expectedVersion:review.version,reviewedDigest:review.digest,evidenceReference:evidence,expiresAt:expiry?new Date(expiry).toISOString():null,ownerAuthorityConfirmed:confirmed})});
 if(!r.ok){setMessage("Approval change rejected. Refresh preview and check release configuration.");setReview(null);return;}
 setReview(null);setListing(null);setEvidence("");setConfirmed(false);setMessage("Registry updated. Refresh preview to verify status; this is not confirmation of live publication.");
 }catch{setMessage("Approval service unavailable.");setReview(null);}finally{setBusy(false);}
 }
 async function preview(){
 setBusy(true);setReview(null);setListing(null);setMessage("");
 try{const r=await authenticatedFetch("/api/v1/admin/publication-preview",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({key:tab+":"+id.trim()})});
 const data=await r.json();if(!r.ok){setMessage(r.status===403?"Administrator access required.":r.status===401?"Sign in required.":"Preview unavailable.");return;}
 setReview({key:tab+":"+id.trim(),digest:data.reviewedDigest,version:data.registry?.version??0,enabled:data.registryEnabled,revoked:!!data.registry?.revoked_at,expiry:data.registry?.expires_at??null});setListing(data.listing);setMessage(data.listing?("Eligible for review; check registry status below."):"No eligible listing found. Check verification, availability, record status and required fields.");
 }catch{setMessage("Preview unavailable.");}finally{setBusy(false);}
 }
 return <main className="container workspace"><Link href="/dashboard/properties">Back to inventory</Link><h1>Website publication review</h1><p>Review the exact public listing fields. Publishing remains disabled until owner authority and release checks are complete.</p><form onSubmit={e=>{e.preventDefault();void preview();}}><label>Inventory tab <select disabled={busy} value={tab} onChange={e=>{setTab(e.target.value);setReview(null);setListing(null);setMessage("");}}><option>Commercial Inventory</option><option>Residential Inventory</option></select></label><label>Property ID <input disabled={busy} required value={id} onChange={e=>{setId(e.target.value);setListing(null);setMessage("");}}/></label><button className="btn" disabled={busy}>{busy?"Checking…":"Preview public listing"}</button></form><p role="status">{message}</p>{listing&&<section className="details-panel"><h2>{listing.title}</h2><p>{listing.location} · {listing.assetType} · {listing.transaction}</p><p>Carpet: {listing.areaSqFt} sq.ft. · ₹{listing.priceInr?.toLocaleString("en-IN")}</p><p>Built-up: {listing.builtUpSqFt??"Not supplied"} · {listing.furnishing||"Furnishing not supplied"}</p><details><summary>Exact public payload</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(listing,null,2)}</pre></details></section>}{review&&<section className="details-panel"><h2>Approval registry</h2><p>{review.version===0?"No approval recorded":review.revoked?"Revoked":review.expiry&&Date.parse(review.expiry)<=Date.now()?"Expired":"Approval recorded; live visibility not confirmed"}</p>{!review.enabled?<p>Approval writes are disabled.</p>:<>{listing&&<><label>Private owner authority evidence reference <input value={evidence} maxLength={500} onChange={e=>setEvidence(e.target.value)}/></label><label>Approval expiry <input type="datetime-local" value={expiry} onChange={e=>setExpiry(e.target.value)}/></label><label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> I have verified owner authority and reviewed the exact public fields.</label><button className="btn" disabled={busy||!confirmed||!evidence.trim()||!expiry} onClick={()=>void change("approve")}>Approve reviewed listing</button></>}{review.version>0&&!review.revoked&&<button className="btn btn-outline" disabled={busy} onClick={()=>void change("revoke")}>Revoke approval</button>}</>}</section>}</main>;
}
