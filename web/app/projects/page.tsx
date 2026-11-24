import Link from "next/link";
import SiteHeader from "../../components/site-header";
import SiteFooter from "../../components/site-footer";
import {sampleProperties} from "../../components/property-card";
import {readApprovedPublicListings} from "../../lib/approved-sheets";
import {groupPublicProjects,projectPath,valueLabel} from "../../lib/property-display";
export const dynamic="force-dynamic";
export default async function ProjectsPage(){
 const approved=await readApprovedPublicListings().catch(()=>[]);
 const isDemo=approved.length===0;
 const groups=groupPublicProjects(isDemo?sampleProperties:approved);
 return <><SiteHeader/><main><section className="directory-intro"><div className="container"><span className="kicker">PROJECT-FIRST INVENTORY</span><h1 className="page-title">Commercial Projects</h1><p className="section-lede">Browse buildings and see their linked lease, sale and investment opportunities.</p></div></section><section className="section"><div className="container">{isDemo?<p className="demo-label">Demonstration project cards only. Live projects will appear when properties are approved for publication.</p>:null}<div className="project-grid">{groups.map((p,i)=><article key={p.slug} className="project-card"><div className="project-visual"><div className={"facade "+(["a","b","c"][i%3])}/><span className="media-pill">{isDemo?"DESIGN SAMPLE":"ILLUSTRATIVE IMAGE"}</span></div><div className="project-card-body"><span className="project-sub">COMMERCIAL PROJECT</span><h3>{p.name}</h3><p>⌖ {p.location}</p><div className="property-specs"><div><small>{isDemo?"SAMPLE UNITS":"APPROVED UNITS"}</small><strong>{p.units.length}</strong></div><div><small>CARPET AREA EXAMPLE</small><strong>{valueLabel(p.units[0]?.areaSqFt)}</strong></div></div><p><Link className="btn" href={projectPath(p.name,p.location)} style={{width:"100%"}}>View project & units →</Link></p></div></article>)}</div>{groups.length===0?<div className="empty-note">There are no approved projects available for public publication.</div>:null}</div></section></main><SiteFooter/></>
}