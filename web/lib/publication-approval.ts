import {createHash} from "node:crypto";
import type {PublicListing} from "./public-inventory";
export type PublicationApproval={property_key:string;payload_sha256:string;expires_at:string;revoked_at:string|null};
export function publicPayloadDigest(listing:PublicListing):string{
 const canonical=JSON.stringify(Object.fromEntries(Object.entries(listing).sort(([a],[b])=>a<b?-1:a>b?1:0)));
 return createHash("sha256").update(canonical).digest("hex");
}
export function approvalAllows(listing:PublicListing,approval:PublicationApproval|undefined,now=Date.now()):boolean{
 if(!approval||approval.property_key!==listing.id||approval.revoked_at!==null)return false;
 const expires=Date.parse(approval.expires_at);
 return Number.isFinite(expires)&&expires>now&&approval.payload_sha256===publicPayloadDigest(listing);
}
