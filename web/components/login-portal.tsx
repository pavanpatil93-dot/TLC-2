"use client";
import {useEffect,useState} from "react";
import {useRouter} from "next/navigation";
const kinds=[{id:"employee",title:"Employee / Admin",description:"Invited team members"},{id:"owner",title:"Property Owner",description:"Manage own submissions"},{id:"developer",title:"Developer",description:"Project submissions"},{id:"broker",title:"Broker / Partner",description:"Submit and track listings"},{id:"client",title:"Client",description:"Save requirements"}] as const;
type Kind=typeof kinds[number]["id"];
type ErrorPayload={error?:string;status?:string};
export default function LoginPortal(){
 const router=useRouter();
 const [kind,setKind]=useState<Kind>("employee"),[email,setEmail]=useState(""),[code,setCode]=useState("");
 const [step,setStep]=useState<"email"|"code">("email");
 const [configured,setConfigured]=useState<boolean|null>(null),[backend,setBackend]=useState(false);
 const [busy,setBusy]=useState(false),[message,setMessage]=useState("");
 useEffect(()=>{fetch("/api/v1/auth/status",{cache:"no-store"}).then(r=>r.json()).then(x=>{
  setConfigured(Boolean(x.authConfigured));setBackend(Boolean(x.databaseConfigured));
 }).catch(()=>setConfigured(false));},[]);
 async function requestCode(e:React.FormEvent){e.preventDefault();if(busy||!configured||!backend)return;
  setBusy(true);setMessage("");
  try{
   const r=await fetch("/api/v1/auth/request-code",{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({email,accountType:kind}),cache:"no-store"});
   const x=await r.json() as ErrorPayload;
   if(!r.ok)throw new Error(x.error==="SIGN_IN_NOT_CONFIGURED"?"Sign-in has not yet been activated.":"Unable to send a code. Check the email and try again.");
   setStep("code");setMessage("If this email is eligible, a sign-in code has been sent. Check your inbox.");
  }catch(e){setMessage(e instanceof Error?e.message:"Unable to request code.")}finally{setBusy(false)}
 }
 async function verifyCode(e:React.FormEvent){e.preventDefault();if(busy)return;
  setBusy(true);setMessage("");
  try{
   const r=await fetch("/api/v1/auth/verify-code",{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({email,code}),cache:"no-store"});
   const x=await r.json() as ErrorPayload;
   if(!r.ok)throw new Error(x.error==="INVALID_OR_EXPIRED_CODE"?"The code has expired or is incorrect.":"Unable to verify this code.");
   router.push("/dashboard");router.refresh();
  }catch(e){setMessage(e instanceof Error?e.message:"Verification failed.")}finally{setBusy(false)}
 }
 return <section className="login-shell"><div className="container login-layout"><div className="login-intro"><span className="kicker">TLC SECURE ACCESS</span><h1>One account. The right workspace.</h1><p>Property search is always public. Sign in to access your own submissions, team responsibilities or account dashboard.</p><div className="login-points"><div>✓ Email code instead of a shared password</div><div>✓ Access approved by role</div><div>✓ Confidential owner and client details stay private</div></div></div><div className="login-panel"><h2>Sign in to TLC</h2><p>Choose your account type and enter your registered email.</p><div className="login-kind-grid">{kinds.map(k=><button type="button" key={k.id} className={"login-kind"+(kind===k.id?" selected":"")} onClick={()=>{setKind(k.id);setMessage("");setStep("email");}}><strong>{k.title}</strong><span>{k.description}</span></button>)}</div>{configured===null?<div className="auth-info">Checking account service…</div>:!configured?<div className="auth-info" role="status"><strong>Secure login is not activated in this preview.</strong><p>A staging identity provider and database connection are required. No test password or administrator bypass is provided.</p></div>:!backend?<div className="auth-info">Identity verification is available, but approved-role access is pending database provisioning.</div>:null}<form onSubmit={step==="email"?requestCode:verifyCode} className="login-form">{step==="email"?<><label htmlFor="auth-email">Email address</label><input id="auth-email" type="email" autoComplete="email" value={email} onChange={e=>setEmail(e.target.value)} required maxLength={254} placeholder="you@company.com"/><button className="btn" type="submit" disabled={busy||!configured||!backend}>{busy?"Sending…":"Send secure code →"}</button></>:<><label htmlFor="auth-code">Enter email code</label><input id="auth-code" inputMode="numeric" pattern="[0-9]{6,8}" autoComplete="one-time-code" value={code} onChange={e=>setCode(e.target.value.replace(/\D/g,"").slice(0,8))} required placeholder="6-digit code"/><button className="btn" type="submit" disabled={busy||!backend||code.length<6}>{busy?"Verifying…":"Verify & continue →"}</button><button type="button" className="auth-text-action" onClick={()=>{setStep("email");setCode("");setMessage("");}}>Use another email</button></>}{message?<p role="status" className="auth-feedback">{message}</p>:null}</form><p className="login-legal">Selecting a role never grants access. Employees must be invited; partner and client accounts require authorization before private features become available.</p></div></div></section>;
}
