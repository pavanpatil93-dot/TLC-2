"use client";
import {useEffect,useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
type AuthStatus={authConfigured?:boolean;databaseConfigured?:boolean};
export default function LoginPortal(){
 const router=useRouter();
 const [email,setEmail]=useState(""),[password,setPassword]=useState(""),[showPassword,setShowPassword]=useState(false);
 const [configured,setConfigured]=useState<boolean|null>(null),[busy,setBusy]=useState(false),[message,setMessage]=useState("");
 useEffect(()=>{fetch("/api/v1/auth/status",{cache:"no-store"}).then(r=>r.json()).then((r:AuthStatus)=>setConfigured(!!r.authConfigured)).catch(()=>setConfigured(false));},[]);
 async function submit(e:React.FormEvent<HTMLFormElement>){
  e.preventDefault();if(busy||!configured)return;
  setBusy(true);setMessage("");
  try{
   const r=await fetch("/api/v1/auth/password",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({email,password}),cache:"no-store"});
   const data=await r.json() as {error?:string};
   if(!r.ok)throw new Error(data.error==="SIGN_IN_NOT_CONFIGURED"?"Login is not yet configured.":"Incorrect email or password, or account access is unavailable.");
   setPassword("");router.replace("/dashboard");router.refresh();
  }catch(err){setMessage(err instanceof Error?err.message:"Sign-in failed. Please try again.")}finally{setBusy(false)}
 }
 return <section className="login-shell"><div className="container login-layout"><div className="login-intro"><span className="kicker">THE LEASING COMPANY</span><h1>Welcome back.</h1><p>Sign in with your email and password. After signing in, choose how you want to use TLC: as a broker, owner, client, tenant or developer.</p><div className="login-points"><div>✓ One secure login for every account type</div><div>✓ Choose your profile after sign-in</div><div>✓ Account permissions remain approval-controlled</div></div></div><div className="login-panel"><h2>Sign in</h2><p>Enter your registered email and password to continue.</p>{configured===null?<div className="auth-info" role="status">Checking sign-in availability…</div>:!configured?<div className="auth-info" role="status"><strong>Sign-in is not available yet.</strong><p>The staging authentication service needs to be configured. No default password or temporary admin bypass is provided.</p></div>:null}<form className="login-form" onSubmit={submit}><label htmlFor="auth-email">Email address</label><input id="auth-email" type="email" autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@company.com" maxLength={254} required/><label htmlFor="auth-password">Password</label><div className="password-input-row"><input id="auth-password" type={showPassword?"text":"password"} autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} placeholder="Enter your password" required/><button type="button" onClick={()=>setShowPassword(v=>!v)} aria-label={showPassword?"Hide password":"Show password"}>{showPassword?"Hide":"Show"}</button></div><button className="btn" type="submit" disabled={busy||!configured}>{busy?"Signing in…":"Sign in →"}</button>{message?<p className="auth-feedback" role="alert">{message}</p>:null}</form><p><Link href="/login?recovery=1">Forgot password?</Link></p><p className="login-legal">Your account type is chosen after login. Selecting a type does not automatically approve property publishing or internal access.</p><p className="login-legal">New user? Contact TLC for an invitation or account setup. <Link href="/properties">Continue browsing properties</Link>.</p></div></div></section>;
}
