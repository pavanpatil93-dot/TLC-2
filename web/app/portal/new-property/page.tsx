import SiteHeader from "../../../components/site-header";
import SiteFooter from "../../../components/site-footer";
import DraftPropertyForm from "../../../components/draft-property-form";
export const metadata={title:"Prepare Property Submission | TLC",robots:{index:false,follow:false}};
export default function NewPropertyPage(){return <><SiteHeader/><main className="container portal-page"><div className="portal-heading"><span className="kicker">BATCH 06 · PROPERTY SUBMISSION</span><h1>Prepare a property listing</h1><p>Build and check a listing draft before owner or broker verification. No record will be posted or published from this preview.</p></div><DraftPropertyForm/></main><SiteFooter/></>}
