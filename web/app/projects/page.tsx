import Link from "next/link";
import SiteHeader from "../../components/site-header";
import SiteFooter from "../../components/site-footer";
import {sampleProperties} from "../../components/property-card";
import {readApprovedPublicListings} from "../../lib/approved-sheets";
import {groupPublicProjects,projectPath,valueLabel} from "../../lib/property-display";
import type {PublicListing} from "../../lib/public-inventory";
const PROPERTY_TYPES=[{value:"office",label:"Office"},{value:"shop",label:"Shop"},{value:"showroom",label:"Showroom"},{value:"warehouse",label:"Warehouse"}] as const;
export const dynamic="force-dynamic";
const validTypes=new Set<string>(PROPERTY_TYPES.map(t=>t.value));
const validPossession=new Set(["any","ready","2026","2027","2028","2029plus"]);
type Props={searchParams:Promise<{search?:string;asset?:string|string[];possession?:string}>};
function matchesPossession(unit:PublicListing,selected:string){
 if(selected==="any")return true;
 const status=(unit.constructionStatus||"").toLowerCase().trim();
 if(selected==="ready")return /^(ready([ -]?to[ -]?move)?|completed|oc[ -]?received|occupation certificate received|ready possession)$/.test(status);
 const date=unit.targetPossessionDate||"";
 const years=date.match(/\b20\d{2}\b/g);
 if(!years||years.length!==1)return false; // No guessing from missing or ambiguous dates
 const year=Number(years[0]);
 return selected==="2029plus"?year>=2029:year===Number(selected);
}
export default async function ProjectsPage({searchParams}:Props){
 const params=await searchParams;
 const search=(params.search||"").trim().slice(0,100);
 const rawTypes=Array.isArray(params.asset)?params.asset:[params.asset||""];
 const assets=Array.from(new Set(rawTypes.flatMap(x=>x.split(",").map(v=>v.trim().toLowerCase())).filter(v=>validTypes.has(v))));
 const possession=validPossession.has(params.possession||"")?params.possession||"any":"any";
 const approved=(await readApprovedPublicListings().catch(()=>[])).filter(unit=>unit.type!=="residential");
 const isDemo=approved.length===0;
 const groups=groupPublicProjects(isDemo?sampleProperties:approved);
 const filtered=groups.filter(p=>(!search||[p.name,p.location].join(" ").toLowerCase().includes(search.toLowerCase()))&&p.units.some(u=>(assets.length===0||assets.some(a=>[u.assetType,u.title,u.description].join(" ").toLowerCase().includes(a)))&&matchesPossession(u,possession)));
 return <><SiteHeader/><main><section className="directory-intro"><div className="container"><span className="kicker">PROJECT-FIRST SEARCH</span><h1 className="page-title">Find Commercial Projects</h1><p className="section-lede">Search by locality, multiple property types and recorded possession timelines.</p></div></section><section className="section"><div className="container"><form className="project-search-form" action="/projects" method="get"><div className="field"><label htmlFor="project-query">LOCATION / PROJECT NAME</label><input id="project-query" name="search" defaultValue={search} placeholder="Project, Kharadi, Viman Nagar..." /></div><fieldset className="project-types-field"><legend>PROPERTY TYPE · SELECT MULTIPLE</legend><div className="project-type-options">{PROPERTY_TYPES.map(t=><label key={t.value} className="project-type-option"><input type="checkbox" name="asset" value={t.value} defaultChecked={assets.includes(t.value)}/><span>{t.label}</span></label>)}</div><small>Leave all unchecked to search every type.</small></fieldset><div className="field"><label htmlFor="project-possession">POSSESSION</label><select id="project-possession" name="possession" defaultValue={possession}><option value="any">Any possession</option><option value="ready">Ready to Move</option><option value="2026">2026</option><option value="2027">2027</option><option value="2028">2028</option><option value="2029plus">2029 or later</option></select></div><button className="btn" type="submit">Search Projects →</button></form><div className="results-head"><div><h2>Project results</h2><small>{filtered.length} {isDemo?"illustrative":"approved"} project{filtered.length===1?"":"s"} matching your search</small></div><Link className="link-more" href="/projects">Clear filters</Link></div>{isDemo?<p className="demo-label">Demonstration project cards only; not current available inventory. Possession dates for sample cards are deliberately unspecified.</p>:null}{possession!=="any"?<p className="demo-label">Possession is matched against explicitly recorded and approved status/date fields. Projects without that information are excluded from the selected possession filter.</p>:null}<div className="project-grid">{filtered.map((p,i)=><article key={p.slug} className="project-card"><div className="project-visual"><div className={"facade "+(["a","b","c"][i%3])}/><span className="media-pill">{isDemo?"DESIGN SAMPLE":"ILLUSTRATIVE IMAGE"}</span></div><div className="project-card-body"><span className="project-sub">COMMERCIAL PROJECT</span><h3>{p.name}</h3><p>⌖ {p.location}</p><div className="property-specs"><div><small>{isDemo?"SAMPLE UNITS":"APPROVED UNITS"}</small><strong>{p.units.length}</strong></div><div><small>CARPET AREA EXAMPLE</small><strong>{valueLabel(p.units[0]?.areaSqFt)}</strong></div></div><p><Link className="btn" href={projectPath(p.name,p.location)} style={{width:"100%"}}>View project & units →</Link></p></div></article>)}</div>{filtered.length===0?<div className="empty-note"><strong>No projects match these filters.</strong><p>Try a different combination of property types or possession dates. Approved project-level possession data is not yet integrated, so some genuine projects may be absent.</p></div>:null}</div></section></main><SiteFooter/></>;
}
