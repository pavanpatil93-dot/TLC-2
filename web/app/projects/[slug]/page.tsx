import Link from "next/link";
import {notFound} from "next/navigation";
import SiteHeader from "../../../components/site-header";
import SiteFooter from "../../../components/site-footer";
import PropertyCard,{sampleProperties} from "../../../components/property-card";
import PropertyGallery from "../../../components/property-gallery";
import {readApprovedPublicListings} from "../../../lib/approved-sheets";
import {groupPublicProjects} from "../../../lib/property-display";
export const dynamic="force-dynamic";
type Props={params:Promise<{slug:string}>};
export default async function ProjectDetails({params}:Props){
 const {slug}=await params;
 const demo=groupPublicProjects(sampleProperties).find(p=>p.slug===slug);
 let project=demo;
 if(!project){const live=await readApprovedPublicListings().catch(()=>[]);project=groupPublicProjects(live.filter(unit=>unit.type!=="residential")).find(p=>p.slug===slug);}
 if(!project)notFound();
 const p=project;
 const lease=p.units.filter(x=>x.transaction==="lease");
 const sale=p.units.filter(x=>x.transaction==="sale");
 const investment=p.units.filter(x=>x.isPreLeased);
 return <><SiteHeader/><main><div className="container details-container"><div className="breadcrumbs"><Link href="/">Home</Link> / <Link href="/projects">Projects</Link> / {p.name}</div><div className="details-head"><div><span className="kicker">{demo?"DESIGN SAMPLE · PROJECT":"APPROVED PROJECT INVENTORY"}</span><h1>{p.name}</h1><p>⌖ {p.location}</p></div><div className="details-price"><small>{demo?"ILLUSTRATIVE":"PUBLISHED UNITS"}</small><strong>{p.units.length} {p.units.length===1?"unit":"units"}</strong></div></div><p className="demo-label">{demo?"This is a demonstration project only. No sample unit is available for transaction.":"Only individually approved, verified and available unit listings are shown. Project photography is illustrative until reviewed."}</p><PropertyGallery isDemo={!!demo}/><div className="details-panel"><h2>Available project units</h2><div className="project-counts"><div><strong>{lease.length}</strong><span>For Lease</span></div><div><strong>{sale.length}</strong><span>Outright Sale</span></div><div><strong>{investment.length}</strong><span>Pre-Leased</span></div></div><p className="section-lede">Unit counts include only the listings shown below. Pre-leased units are also included in outright sale counts.</p></div><div className="details-panel"><h2>Browse individual units</h2><div className="listing-grid">{p.units.map((unit,i)=><PropertyCard key={unit.id} property={{...unit,sample:!!demo}} index={i}/>)}</div></div><div className="details-panel"><Link className="btn btn-outline" href="/projects">← Back to projects</Link></div></div></main><SiteFooter/></>
}
