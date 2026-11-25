import {NextResponse} from "next/server";
import {resolveVerifiedPrincipal,hasPermission,privateNoStore,withSubjectTransaction} from "../../../../../lib/server/authorization";
import {readPublicationPreview} from "../../../../../lib/approved-sheets";
import {publicPayloadDigest} from "../../../../../lib/publication-approval";
export const runtime="nodejs";
export async function POST(request:Request){
 const reply=(body:unknown,status=200)=>NextResponse.json(body,{status,headers:privateNoStore});
 const p=await resolveVerifiedPrincipal(request);
 if(!p)return reply({error:"AUTHENTICATION_REQUIRED"},401);
 if(!hasPermission(p,"account:manage")||!hasPermission(p,"property:publish"))return reply({error:"FORBIDDEN"},403);
 if(process.env.TLC_MASTER_APPROVAL_WRITES!=="enabled")return reply({error:"APPROVAL_RELEASE_GATE_CLOSED"},503);
 let b:Record<string,unknown>;try{const raw=await request.json();if(!raw||typeof raw!=="object"||Array.isArray(raw))throw Error();b=raw;}catch{return reply({error:"INVALID_JSON"},400);}
 const key=b.key,action=b.action,version=b.expectedVersion;
 if(typeof key!=="string"||!/^(Commercial Lease Inventory|Commercial Resale Inventory|Residential Under Construction|Residential Resale|Residential Rentals):[A-Za-z0-9_-]{1,64}$/.test(key)||!Number.isSafeInteger(version)||Number(version)<0||!["approve","revoke"].includes(String(action)))return reply({error:"INVALID_REQUEST"},422);
 try{
 let digest="",evidence="",expiry="";
 if(action==="approve"){
 if(b.ownerAuthorityConfirmed!==true||typeof b.evidenceReference!=="string"||!b.evidenceReference.trim()||b.evidenceReference.length>500||typeof b.expiresAt!=="string")return reply({error:"OWNER_AUTHORITY_EVIDENCE_REQUIRED"},422);
 evidence=b.evidenceReference.trim();expiry=b.expiresAt;
 const end=Date.parse(expiry),now=Date.now();if(!Number.isFinite(end)||end<=now||end>now+90*86400000)return reply({error:"INVALID_EXPIRY"},422);
 const [listing]=await readPublicationPreview(new Set([key]));if(!listing)return reply({error:"NOT_ELIGIBLE"},409);
 digest=publicPayloadDigest(listing);if(b.reviewedDigest!==digest)return reply({error:"PREVIEW_CHANGED"},409);
 }
 const result=await withSubjectTransaction(p.subject,async db=>{
 const q=action==="revoke"?await db.query("UPDATE tlc.master_publication_approvals SET revoked_at=now(),version=version+1 WHERE property_key=$1 AND version=$2 AND revoked_at IS NULL RETURNING version",[key,version]):Number(version)===0?await db.query("INSERT INTO tlc.master_publication_approvals(property_key,evidence_reference,payload_sha256,approved_by,expires_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT DO NOTHING RETURNING version",[key,evidence,digest,p.accountId,expiry]):await db.query("UPDATE tlc.master_publication_approvals SET evidence_reference=$3,payload_sha256=$4,approved_by=$5,approved_at=now(),expires_at=$6,revoked_at=NULL,version=version+1 WHERE property_key=$1 AND version=$2 RETURNING version",[key,version,evidence,digest,p.accountId,expiry]);
 if(!q.rowCount)return null;
 await db.query("INSERT INTO tlc.master_publication_events(property_key,actor_id,action,version) VALUES($1,$2,$3,$4)",[key,p.accountId,action,q.rows[0].version]);
 return q.rows[0].version;
 },true);
 return result===null?reply({error:"VERSION_CONFLICT"},409):reply({version:result,action,livePublicationChanged:false});
 }catch{return reply({error:"SERVICE_UNAVAILABLE"},503);}
}
