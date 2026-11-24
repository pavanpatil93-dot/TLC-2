/**
 * Safe public inventory mapping for TLC Master Sheet.
 * No owner, broker, source, remarks, phone, WhatsApp Pitch or CRM fields are exported.
 * Publication is always explicit, and verified/available are additionally required.
 */
export type PublicListing = {
  id: string;
  title: string;
  location: string;
  type: "lease" | "resale" | "residential";
  areaSqFt: number | null;
  priceInr: number | null;
  transaction: "lease" | "sale";
  description: string;
  projectName?: string;
  assetType?: string;
  floor?: string;
  builtUpSqFt?: number | null;
  furnishing?: string;
  currentRentInr?: number | null;
  isPreLeased?: boolean;
  yieldPct?: number | null;
  isUnderConstruction?: boolean;
  constructionStatus?: string;
  targetPossessionDate?: string;
};

export const INVENTORY_TABS = ["Commercial Inventory", "Residential Inventory"] as const;
export type InventoryTab = (typeof INVENTORY_TABS)[number];
const norm = (s: string) => s.trim().toLowerCase();
const amount = (s: string): number | null => {
 const v=s.replace(/[,₹]/g,"").trim();
 if(!/^\d+(\.\d+)?$/.test(v))return null;
 const n=Number(v);return Number.isFinite(n)&&n>0&&n<=Number.MAX_SAFE_INTEGER?n:null;
};
// Reject contact-like content even in otherwise public text columns.
const safe = (s:string) => s.length<=100 && !/[@<>\r\n]|https?:|www\.|(?:\d[\s()+.-]*){10,}/i.test(s) ? s.trim() : "";
export function approvedPublicationKeys(value:string|undefined):ReadonlySet<string>{
 try {const a:unknown=JSON.parse(value??"[]");return new Set(Array.isArray(a)?a.filter((v):v is string=>typeof v==="string"&&INVENTORY_TABS.some(t=>v.startsWith(t+":"))):[]);}catch{return new Set();}
}
export function toPublicListings(sheet:InventoryTab,values:string[][],approved:ReadonlySet<string>):PublicListing[]{
 const [raw,...rows]=values;if(!raw)return [];
 const h=raw.map(s=>s.trim());
 const required=["Property ID","Project / Building","Location","Property Type","Transaction Type","Carpet SqFt","Built-up SqFt","Sale Price INR","Rent INR/Month","Availability Status","Verification Status","Record Status"];
 if(new Set(h).size!==h.length||required.some(k=>!h.includes(k)))return [];
 const get=(r:string[],k:string)=>(r[h.indexOf(k)]??"").trim();
 const counts=new Map<string,number>();
 for(const r of rows){const id=get(r,"Property ID");counts.set(id,(counts.get(id)??0)+1);}
 const out:PublicListing[]=[];
 for(const r of rows){
  const id=get(r,"Property ID"), key=sheet+":"+id;
  if(!/^[A-Za-z0-9_-]{1,64}$/.test(id)||counts.get(id)!==1||!approved.has(key))continue;
  if(norm(get(r,"Record Status"))!=="active"||!["verified","verified confirmed"].includes(norm(get(r,"Verification Status")))||!["available","available - verified","available - pre-leased"].includes(norm(get(r,"Availability Status"))))continue;
  const tx=norm(get(r,"Transaction Type"));
  const pre=["pre-leased","preleased","pre-leased sale"].includes(tx)||norm(get(r,"Availability Status"))==="available - pre-leased";
  const lease=["lease","rent","rental"].includes(tx);
  if(!lease&&!pre&&!["sale","resale","outright"].includes(tx))continue;
  if(lease&&pre)continue;
  const project=safe(get(r,"Project / Building")),location=safe(get(r,"Location")),assetType=safe(get(r,"Property Type"));
  const areaSqFt=amount(get(r,"Carpet SqFt")),priceInr=amount(get(r,lease?"Rent INR/Month":"Sale Price INR"));
  if(!project||!location||!assetType||!areaSqFt||!priceInr)continue;
  const furnishing=safe(get(r,"Furnishing"));
  const rent=pre?amount(get(r,"Rent INR/Month")):null;
  out.push({id:key,title:project,projectName:project,location,assetType,type:sheet==="Residential Inventory"?"residential":lease?"lease":"resale",transaction:lease?"lease":"sale",areaSqFt,priceInr,builtUpSqFt:amount(get(r,"Built-up SqFt")),furnishing,description:"",isPreLeased:pre,currentRentInr:rent,yieldPct:rent?rent*1200/priceInr:null});
 }
 return out;
}
export function deduplicate(listings:PublicListing[]):PublicListing[]{
 const counts=new Map<string,number>();for(const p of listings)counts.set(p.id,(counts.get(p.id)??0)+1);
 return listings.filter(p=>counts.get(p.id)===1);
}
