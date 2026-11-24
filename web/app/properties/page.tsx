import Link from "next/link";
import PropertyFinder from "./property-finder";
export const dynamic = "force-dynamic";
export default function PropertiesPage(){return <><header className="container header"><Link href="/" className="logo">THE LEASING <span>COMPANY</span></Link><Link href="/" className="btn secondary">Home</Link></header><main className="section alt" style={{minHeight:"75vh"}}><div className="container"><div className="label">Our inventory</div><h1 style={{fontSize:38,margin:"14px 0"}}>Explore properties</h1><p className="intro">Only listings approved for public sharing appear here. Private owner, source, brokerage and CRM information are excluded.</p><PropertyFinder/></div></main></>}
