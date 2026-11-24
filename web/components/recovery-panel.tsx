"use client";
import {useEffect,useState} from "react";
export default function RecoveryPanel(){
 const [mode,setMode]=useState(""),[token,setToken]=useState(""),[email,setEmail]=useState(""),[password,setPassword]=useState(""),[message,setMessage]=useState(""),[busy,setBusy]=useState(false);
 useEffect(()=>{
 const p=new URLSearchParams(window.location.hash.slice(1));
 if(p.get("type")==="recovery"||p.get("type")==="invite"){
 setToken(p.get("access_token")||"");setMode("reset");window.history.replaceState(null,"",window.location.pathname+window.location.search);
 }else if(p.has("error")){setMode("request");setMessage("This link is invalid or expired. Request a fresh link.");window.history.replaceState(null,"",window.location.pathname+window.location.search);}
 else if(window.location.pathname==="/login"&&new URLSearchParams(window.location.search).has("recovery"))setMode("request");
 },[]);
 if(!mode)return null;
 async function submit(e:React.FormEvent<HTMLFormElement>){
 e.preventDefault();if(busy)return;setBusy(true);setMessage("");
 try{const r=await fetch("/api/v1/auth/recovery",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(mode==="reset"?{action:"reset",token,password}:{action:"request",email}),cache:"no-store"});
 if(!r.ok)throw new Error();
 if(mode==="reset"){setToken("");setPassword("");setMode("done");setMessage("Password updated. Sign in with your new password.");}
 else setMessage("If your account is eligible, a recovery email will be sent. Check your inbox.");
 }catch{setMessage("Unable to complete this request. Request a fresh recovery link or try again later.");}finally{setBusy(false);}
 }
 return <section role="dialog" aria-modal="true" aria-label="Password recovery" style={{position:"fixed",inset:0,zIndex:9999,background:"#f6f5f2",display:"grid",placeItems:"center",padding:20}}><div className="login-panel" style={{width:"100%",maxWidth:480}}><h2>{mode==="reset"?"Set a new password":mode==="done"?"Password updated":"Recover your password"}</h2>{mode!=="done"&&<form className="login-form" onSubmit={submit}>{mode==="reset"?<><label htmlFor="new-password">New password (at least 12 characters)</label><input id="new-password" type="password" autoComplete="new-password" minLength={12} maxLength={1024} required value={password} onChange={e=>setPassword(e.target.value)}/></>:<><label htmlFor="recovery-email">Email address</label><input id="recovery-email" type="email" required value={email} onChange={e=>setEmail(e.target.value)}/></>}<button className="btn" disabled={busy||(mode==="reset"&&!token)}>{busy?"Please wait…":mode==="reset"?"Update password":"Send recovery email"}</button></form>}{message&&<p role="status">{message}</p>}<a href="/login">Return to sign in</a></div></section>;
}
