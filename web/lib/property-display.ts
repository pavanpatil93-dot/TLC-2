import type {PublicListing} from "./public-inventory";
export function amountLabel(value:number|null,transaction:"lease"|"sale"):string {
  if(value===null||value<=0)return "Price on request";
  const short=value>=10000000?(value/10000000).toFixed(2)+" Cr":value>=100000?(value/100000).toFixed(2)+" L":value.toLocaleString("en-IN");
  return "₹"+short+(transaction==="lease"?"/month":"");
}
export function valueLabel(n:number|null|undefined,unit="sq.ft."):string {return n&&n>0?n.toLocaleString("en-IN")+" "+unit:"Not provided";}
export function propertyPath(id:string){return "/properties/"+encodeURIComponent(id);}
export function projectSlug(project:string,location:string){
 const key=[project,location].join("--").normalize("NFKD").toLowerCase();
 return key.replace(/[^a-z0-9]+/g,"-").replace(/^-+|-+$/g,"").slice(0,130);
}
export function projectPath(project:string,location:string){return "/projects/"+projectSlug(project,location);}
export type PublicProject={slug:string;name:string;location:string;units:PublicListing[]};
export function groupPublicProjects(listings:PublicListing[]):PublicProject[]{
 const groups=new Map<string,PublicProject>();
 for(const unit of listings){
  const name=unit.projectName?.trim()||unit.title.split(" · ")[0].trim();
  if(!name)continue;
  const slug=projectSlug(name,unit.location);
  if(!slug)continue;
  const current=groups.get(slug);
  if(current)current.units.push(unit);else groups.set(slug,{slug,name,location:unit.location,units:[unit]});
 }
 return Array.from(groups.values()).sort((a,b)=>a.name.localeCompare(b.name));
}
export function propertyCategory(p:PublicListing){
 if(p.isPreLeased)return "Pre-Leased Investment";
 if(p.transaction==="lease")return "For Lease";
 return "Outright Sale";
}
