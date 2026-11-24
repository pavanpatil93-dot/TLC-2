import "server-only";
/** Batch 03 fail-closed identity/authorization contract.
 * Batch 04 must integrate a real, verified auth provider.
 * Do not trust X-User-Id, client-supplied role names, name/mobile, or UI state.
 */
export type Permission =
 "property:read_private"|"property:create"|"property:edit"|"property:archive"|
 "property:publish"|"project:manage"|"submission:review"|"account:manage";
export type VerifiedPrincipal={accountId:string;subject:string;permissions:ReadonlySet<Permission>};
export async function resolveVerifiedPrincipal(_request:Request):Promise<VerifiedPrincipal|null>{
 // Deliberately deny: no cryptographic identity/session verifier is configured yet.
 return null;
}
export function hasPermission(principal:VerifiedPrincipal|null,permission:Permission):boolean{
 return !!principal&&principal.permissions.has(permission);
}
export const privateNoStore={"Cache-Control":"private, no-store, max-age=0","X-Content-Type-Options":"nosniff"} as const;
