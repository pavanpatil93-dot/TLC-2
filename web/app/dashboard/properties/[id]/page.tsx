"use client";
import {useEffect,useState} from "react";
import SiteHeader from "../../../../components/site-header";import SiteFooter from "../../../../components/site-footer";import InventoryEditor from "../../../../components/inventory-editor";
import {authenticatedFetch} from "../../../../lib/auth-client";
type Item={id:string;property_code:string|null;unit_number:string|null;asset_type:string;transaction_type:string;carpet_sqft:string|null;builtup_sqft:string|null;asking_rent_inr:string|null;asking_price_inr:string|null;furnishing:string|null;project_id:string|null;version:number;publication_status:string};
export default function EditProperty({params}:{params:Promise<{id:string}>}){const [item,setItem]=useState<Item|null>(null),[message,setMessage]=useState("Loading record…");
useEffect(()=>{params.then(({id})=>authenticatedFetch("/api/v1/admin/properties/"+encodeURIComponent(id)).then(async r=>{const x=await r.json();if(r.ok){setItem(x.item);setMessage("");}else setMessage(x.error||"Unavailable");})).catch(()=>setMessage("Secure service unavailable"));},[params]);
return <><SiteHeader/><main className="container workspace"><span className="kicker">EMPLOYEE INVENTORY</span><h1>Edit property</h1><p className="section-lede">Version-based edits prevent silent overwrite. Published properties need manager review first.</p>{item?<InventoryEditor key={item.id} item={item}/>:<div className="empty-note">{message}</div>}</main><SiteFooter/></>}
