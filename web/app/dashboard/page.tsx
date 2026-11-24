import SiteHeader from "../../components/site-header";
import SiteFooter from "../../components/site-footer";
import AccountDashboard from "../../components/account-dashboard";
export const metadata={title:"Account | The Leasing Company",robots:{index:false,follow:false}};
export default function DashboardPage(){return <><SiteHeader/><AccountDashboard/><SiteFooter/></>}
