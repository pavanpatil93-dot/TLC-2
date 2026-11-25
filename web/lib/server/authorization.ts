import "server-only";
import {readCookieHeader,ACCESS_COOKIE} from "./auth-cookies";
import {verifyAccessToken} from "./auth-provider";
import {resolveAccountProfile,type AccountRole} from "./account-profile";
export type Permission =
 "property:read_private"|"property:create"|"property:edit"|"property:archive"|
 "property:publish"|"project:manage"|"submission:review"|"account:manage";
const ALL:Permission[]=["property:read_private","property:create","property:edit","property:archive","property:publish","project:manage","submission:review","account:manage"];
const grants:Record<AccountRole,Permission[]>={
 admin:ALL,
 commercial_manager:["property:read_private","property:create","property:edit","project:manage","submission:review"],
 residential_manager:["property:read_private","property:create","property:edit","project:manage","submission:review"],
 inventory_manager:["property:read_private","property:create","property:edit","project:manage"],
 sales_executive:["property:read_private","property:create"],
 read_only:["property:read_private"],
 owner:[],developer:[],broker:[],client:[],tenant:[]
};
export type VerifiedPrincipal={
 accountId:string;subject:string;roles:readonly AccountRole[];
 displayName:string;accountKind:string;permissions:ReadonlySet<Permission>;
};
/** Cryptographic identity is verified with the external provider before any DB role lookup.
 * No X-Role, user ID headers, login role selector, or browser-supplied permissions accepted.
 */
export async function resolveVerifiedPrincipal(request:Request):Promise<VerifiedPrincipal|null>{
 const token=readCookieHeader(request,ACCESS_COOKIE);
 if(!token)return null;
 const verified=await verifyAccessToken(token);
 if(!verified)return null;
 const account=await resolveAccountProfile(verified.id);
 if(!account||account.roles.length===0)return null;
 // External accounts must never inherit internal employee permissions, even if misassigned a role.
 const permissions=new Set<Permission>(account.accountKind === "employee" ? account.roles.flatMap(role=>grants[role] ?? []) : []);
 return {accountId:account.id,subject:verified.id,roles:account.roles,displayName:account.displayName,accountKind:account.accountKind,permissions};
}
export function hasPermission(principal:VerifiedPrincipal|null,permission:Permission):boolean{
 return !!principal&&principal.permissions.has(permission);
}
export const privateNoStore={"Cache-Control":"private, no-store, max-age=0","X-Content-Type-Options":"nosniff"} as const;
