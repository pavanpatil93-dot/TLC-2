"use client";
import {useEffect,useRef,useState} from "react";
import {useSearchParams} from "next/navigation";
import Link from "next/link";
import SiteHeader from "../../components/site-header";import SiteFooter from "../../components/site-footer";
type Turnstile={render:(container:HTMLElement,options:{sitekey:string;callback:(token:string)=>void;"expired-callback":()=>void})=>string|number;remove?:(id:string|number)=>void};
declare global{interface Window{turnstile?:Turnstile}}
function EnquireForm(){const params=useSearchParams(),ref=useRef<HTMLDivElement>(null);
 const [name,setName]=useState(""),[phone,setPhone]=useState("+91"),[location,setLocation]=useState(""),[requirement,setRequirement]=useState(""),[consent,setConsent]=useState(false),[captcha,setCaptcha]=useState(""),[message,setMessage]=useState(""),[loading,setLoading]=useState(false);
 const publicKey=process.env.NEXT_PUBLIC_TLC_TURNSTILE_SITE_KEY||"";
 useEffect(()=>{if(!publicKey||!ref.current)return;let widget:string|number|undefined;let canceled=false;
  const script=document.createElement("script");script.src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";script.async=true;
  script.onload=()=>{if(canceled||!ref.current||!window.turnstile)return;widget=window.turnstile.render(ref.current,{sitekey:publicKey,callback:t=>setCaptcha(t),"expired-callback":()=>setCaptcha("")});};
  document.head.appendChild(script);return()=>{canceled=true;if(widget!==undefined)window.turnstile?.remove?.(widget);script.remove();};
 },[publicKey]);
 async function submit(e:React.FormEvent<HTMLFormElement>){e.preventDefault();setLoading(true);setMessage("");try{
  const res=await fetch("/api/v1/enquiries",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({name,phone,location,requirement,propertyRef:params.get("property")||"",consent,captchaToken:captcha})});
  const d=await res.json();setMessage(res.ok?"Enquiry received privately. Our team will review it.":d.error||"Unable to submit. Please try later.");
 }catch{setMessage("Enquiry service unavailable.");}finally{setLoading(false);}}
 const active=Boolean(publicKey);
 return <form className="property-editor" onSubmit={e=>void submit(e)}><div className="editor-fields"><div className="field"><label htmlFor="enquiry-name">Your Name *</label><input id="enquiry-name" required minLength={2} maxLength={120} value={name} onChange={e=>setName(e.target.value)} autoComplete="name"/></div><div className="field"><label htmlFor="enquiry-phone">Phone in international format *</label><input id="enquiry-phone" required pattern="\+[1-9][0-9]{7,14}" title="Example: +919876543210" value={phone} onChange={e=>setPhone(e.target.value)} autoComplete="tel"/></div><div className="field"><label htmlFor="enquiry-location">Preferred Locality</label><input id="enquiry-location" maxLength={160} value={location} onChange={e=>setLocation(e.target.value)} placeholder="Kharadi, Baner, Viman Nagar..."/></div></div><div className="field"><label htmlFor="enquiry-notes">Requirement *</label><textarea id="enquiry-notes" required minLength={5} maxLength={2000} rows={5} value={requirement} onChange={e=>setRequirement(e.target.value)} placeholder="Office/shop, carpet area, budget, move-in date, other needs"/></div><label className="checkbox-row"><input type="checkbox" required checked={consent} onChange={e=>setConsent(e.target.checked)}/>I agree to be contacted regarding my commercial property requirement.</label>{active?<div ref={ref} aria-label="Human verification challenge"/>:<div className="empty-note">Private enquiry form will activate after anti-spam verification and CRM storage are configured.</div>}<button className="btn" disabled={!active||!captcha||!consent||loading} type="submit">{loading?"Submitting…":"Send Enquiry →"}</button>{message?<p role="status" className="form-status">{message}</p>:null}<p><Link href="/properties">← Back to Properties</Link></p></form>;
}
export default function EnquirePage(){return <><SiteHeader/><main className="container workspace"><span className="kicker">COMMERCIAL PROPERTY ADVISORY</span><h1>Request property assistance</h1><p className="section-lede">Tell us your requirement. We handle enquiries privately and never publicly display client information.</p><EnquireForm/></main><SiteFooter/></>}
