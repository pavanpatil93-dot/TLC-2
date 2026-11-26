"use client";
import {useState} from "react";
import Link from "next/link";
import {authenticatedFetch} from "../../../lib/auth-client";
import {INVENTORY_TABS,type PublicListing} from "../../../lib/public-inventory";
export default function PublicationReview(){
 const [tab,setTab]=useState<string>(INVENTORY_TABS[0]),[id,setId]=useState(""),[listing,setListing]=useState<PublicListing|null>(null),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
 const [review,setReview]=useState<{key:string;digest:string|null;version:number;enabled:boolean;revoked:boolean;expiry:string|null}|null>(null);
 const [evidence,setEvidence]=useState(""),[expiry,setExpiry]=useState(""),[confirmed,setConfirmed]=useState(false);
 const [diagnostic,setDiagnostic]=useState<{sheetsConnected:boolean;publicationEnabled:boolean;approvalWritesEnabled:boolean;tabs:{tab:string;rows:number;eligible:number;schemaValid:boolean;rejected:Record<string,number>}[]}|null>(null);
 const [diagnosticMessage,setDiagnosticMessage]=useState(""),[checking,setChecking]=useState(false);
 async function checkInventory(){
 setChecking(true);setDiagnostic(null);setDiagnosticMessage("");
 try{const r=await authenticatedFetch("/api/v1/admin/inventory-diagnostic",{method:"GET"});
 if(!r.ok){setDiagnosticMessage(r.status===401?"Sign in required.":r.status===403?"Administrator access required.":"Connectivity check unavailable.");return;}
 setDiagnostic(await r.json());setDiagnosticMessage("Google Sheets runtime read succeeded. Eligibility is not publication approval.");
 }catch{setDiagnosticMessage("Connectivity check unavailable.");}finally{setChecking(false);}
 }
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
 setBusy(true);setReview(null);setListing(null);setMessage("");setEvidence("");setConfirmed(false);setExpiry("");
 try{const r=await authenticatedFetch("/api/v1/admin/publication-preview",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({key:tab+":"+id.trim()})});
 const data=await r.json();if(!r.ok){setMessage(r.status===403?"Administrator access required.":r.status===401?"Sign in required.":"Preview unavailable.");return;}
 setReview({key:tab+":"+id.trim(),digest:data.reviewedDigest,version:data.registry?.version??0,enabled:data.registryEnabled,revoked:!!data.registry?.revoked_at,expiry:data.registry?.expires_at??null});setListing(data.listing);setMessage(data.listing?("Eligible for review; check registry status below."):"No eligible listing found. Check verification, availability, record status and required fields.");
 }catch{setMessage("Preview unavailable.");}finally{setBusy(false);}
 }
 return <main className="container workspace"><Link href="/dashboard/properties">Back to inventory</Link><h1>Website publication review</h1><p>Review the exact public listing fields. Publishing remains disabled until owner authority and release checks are complete.</p><section className="details-panel"><h2>Master Sheet connectivity</h2><p>Read-only check of inventory eligibility. No properties are approved or published by this action.</p><button type="button" className="btn" disabled={checking} onClick={()=>void checkInventory()}>{checking?"Checking…":"Check Master Sheet connection"}</button><p role="status">{diagnosticMessage}</p>{diagnostic&&<><p>Publication: {diagnostic.publicationEnabled?"Enabled":"Disabled"} · Approval writes: {diagnostic.approvalWritesEnabled?"Enabled":"Disabled"}</p><div style={{overflowX:"auto"}}><table><thead><tr><th>Inventory tab</th><th>Schema</th><th>Records</th><th>Eligible for review</th><th>Rejection reasons</th></tr></thead><tbody>{diagnostic.tabs.map(t=><tr key={t.tab}><td>{t.tab}</td><td>{t.schemaValid?"Valid":"Invalid"}</td><td>{t.schemaValid?t.rows:"—"}</td><td>{t.schemaValid?t.eligible:"—"}</td><td>{Object.entries(t.rejected).map(([reason,count])=>reason.replaceAll("_"," ")+": "+count).join("; ")||"—"}</td></tr>)}</tbody></table></div></>}</section><form onSubmit={e=>{e.preventDefault();void preview();}}><label>Inventory tab <select disabled={busy} value={tab} onChange={e=>{setTab(e.target.value);setReview(null);setListing(null);setMessage("");}}>{INVENTORY_TABS.map(t=><option key={t}>{t}</option>)}</select></label><label>Unit ID <input disabled={busy} required value={id} onChange={e=>{setId(e.target.value);setReview(null);setListing(null);setMessage("");}}/></label><button className="btn" disabled={busy}>{busy?"Checking…":"Preview public listing"}</button></form><p role="status">{message}</p>{listing&&<section className="details-panel"><h2>{listing.title}</h2><p>{listing.location} · {listing.assetType} · {listing.transaction}</p><p>Carpet: {listing.areaSqFt} sq.ft. · ₹{listing.priceInr?.toLocaleString("en-IN")}</p><p>Built-up: {listing.builtUpSqFt??"Not supplied"} · {listing.furnishing||"Furnishing not supplied"}</p><details><summary>Exact public payload</summary><pre style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{JSON.stringify(listing,null,2)}</pre></details></section>}{review&&<section className="details-panel"><h2>Approval registry</h2><p>{review.version===0?"No approval recorded":review.revoked?"Revoked":review.expiry&&Date.parse(review.expiry)<=Date.now()?"Expired":"Approval recorded; live visibility not confirmed"}</p>{!review.enabled?<p>Approval writes are disabled.</p>:<>{listing&&<><label>Private owner authority evidence reference <input value={evidence} maxLength={500} onChange={e=>setEvidence(e.target.value)}/></label><label>Approval expiry <input type="datetime-local" value={expiry} onChange={e=>setExpiry(e.target.value)}/></label><label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> I have verified owner authority and reviewed the exact public fields.</label><button className="btn" disabled={busy||!confirmed||!evidence.trim()||!expiry} onClick={()=>void change("approve")}>Approve reviewed listing</button></>}{review.version>0&&!review.revoked&&<button className="btn btn-outline" disabled={busy} onClick={()=>void change("revoke")}>Revoke approval</button>}</>}</section>}</main>;
}
