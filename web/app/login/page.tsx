import SiteHeader from "../../components/site-header";
import SiteFooter from "../../components/site-footer";
import LoginPortal from "../../components/login-portal";
export const metadata={title:"Secure Login | The Leasing Company",robots:{index:false,follow:false}};
export default function LoginPage(){return <><SiteHeader/><main><LoginPortal/></main><SiteFooter/></>}
