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

export const INVENTORY_TABS = ["Commercial Lease Inventory","Commercial Resale Inventory","Residential Under Construction","Residential Resale","Residential Rentals"] as const;
export type InventoryTab = (typeof INVENTORY_TABS)[number];
const schema = {
 "Commercial Lease Inventory": ["Project / Building Name","Asset Type","Total Carpet Area (Sq Ft)","Asking Rent / Month (₹)","Condition / Fit-out"],
 "Commercial Resale Inventory": ["Project / Building Name","Asset Type","Total Carpet Area (Sq Ft)","Base / Agreement Value (₹)","Condition / Fit-out"],
 "Residential Under Construction": ["Project Name","Configuration","Carpet Area (Sq Ft)","Agreement Value (₹)",""],
 "Residential Resale": ["Society / Project Name","Configuration","Carpet Area (Sq Ft)","Asking Price (₹)","Condition & Furnishing"],
 "Residential Rentals": ["Society / Project Name","Configuration","Carpet Area (Sq Ft)","Asking Rent / Month (₹)","Condition & Furnishing"]
} as const;
const norm = (s:string)=>s.trim().toLowerCase();
const amount = (s:string):number|null=>{
 const v=s.replace(/[,₹]/g,"").trim();
 if(!/^\d+(\.\d+)?$/.test(v))return null;
 const n=Number(v);return Number.isFinite(n)&&n>0&&n<=Number.MAX_SAFE_INTEGER?n:null;
};
const safe=(s:string)=>s.length<=100&&!/[@<>\r\n]|https?:|www\.|(?:\d[\s()+.-]*){10,}/i.test(s)?s.trim():"";
export function approvedPublicationKeys(value:string|undefined):ReadonlySet<string>{
 try{const a:unknown=JSON.parse(value??"[]");return new Set(Array.isArray(a)?a.filter((v):v is string=>typeof v==="string"&&INVENTORY_TABS.some(t=>v.startsWith(t+":"))):[]);}catch{return new Set();}
}
export function toPublicListings(sheet:InventoryTab,values:string[][],approved:ReadonlySet<string>):PublicListing[]{
 const [raw,...rows]=values;if(!raw||!schema[sheet])return [];
 const [projectCol,assetCol,areaCol,priceCol,fitCol]=schema[sheet];
 const h=raw.map(s=>s.trim()), named=h.filter(Boolean);
 const required=["Unit ID","Location","Inventory Status","Verification Status","Duplicate Check",projectCol,assetCol,areaCol,priceCol];
 if(new Set(named).size!==named.length||required.some(k=>!h.includes(k)))return [];
 const get=(r:string[],k:string)=>k?(r[h.indexOf(k)]??"").trim():"";
 const counts=new Map<string,number>();
 for(const r of rows){const id=get(r,"Unit ID");counts.set(id,(counts.get(id)??0)+1);}
 const out:PublicListing[]=[];
 for(const r of rows){
  const id=get(r,"Unit ID"),key=sheet+":"+id;
  if(!/^[A-Za-z0-9_-]{1,64}$/.test(id)||counts.get(id)!==1||!approved.has(key))continue;
  const status=norm(get(r,"Inventory Status"));
  if(!["available","available - verified","available - pre-leased"].includes(status)||!["confirmed / verified","verified","verified confirmed"].includes(norm(get(r,"Verification Status")))||norm(get(r,"Duplicate Check"))!=="clear")continue;
  const lease=sheet==="Commercial Lease Inventory"||sheet==="Residential Rentals";
  const commercial=sheet==="Commercial Lease Inventory"||sheet==="Commercial Resale Inventory";
  const pre=sheet==="Commercial Resale Inventory"&&(status==="available - pre-leased"||["yes","pre-leased","preleased"].includes(norm(get(r,"Pre-Leased Status"))));
  if(status==="available - pre-leased"&&!pre)continue;
  const project=safe(get(r,projectCol)),location=safe(get(r,"Location")),assetType=safe(get(r,assetCol));
  const areaSqFt=amount(get(r,areaCol)),total=amount(get(r,"Total Carpet Area (Sq Ft)")),priceInr=amount(get(r,priceCol));
  if(!project||!location||!assetType||!areaSqFt||!priceInr||(total!==null&&total!==areaSqFt))continue;
  const rent=pre?amount(get(r,"Current Monthly Rent (₹)")):null;
  out.push({id:key,title:project,projectName:project,location,assetType,type:commercial?(lease?"lease":"resale"):"residential",transaction:lease?"lease":"sale",areaSqFt,priceInr,builtUpSqFt:amount(get(r,"Total Built-Up Area (Sq Ft)")),furnishing:safe(get(r,fitCol)),description:"",isPreLeased:pre,currentRentInr:rent,yieldPct:rent?rent*1200/priceInr:null,isUnderConstruction:sheet==="Residential Under Construction"});
 }
 return out;
}
export function deduplicate(listings:PublicListing[]):PublicListing[]{
 const counts=new Map<string,number>();for(const p of listings)counts.set(p.id,(counts.get(p.id)??0)+1);
 return listings.filter(p=>counts.get(p.id)===1);
}
