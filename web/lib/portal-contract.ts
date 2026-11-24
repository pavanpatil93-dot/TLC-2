/** Batch 04–06 public-safe UI contract. NOT an authorization source. Server must authenticate. */
export const accountKinds=["client","owner","developer","broker","employee"] as const;
export type AccountKind=typeof accountKinds[number];
export const accountMeta:Record<AccountKind,{label:string;summary:string}>={
 client:{label:"Client",summary:"Search, shortlist and enquire about commercial properties."},
 owner:{label:"Owner",summary:"Submit your own commercial property for verification."},
 developer:{label:"Developer",summary:"Propose project profiles and available units for review."},
 broker:{label:"Broker",summary:"Submit sourced properties for the internal approval queue."},
 employee:{label:"Employee / Admin",summary:"Manage internal inventory, reviews and publication under assigned permissions."}
};
export const assetTypes=["office","shop","showroom","warehouse","other"] as const;
export type AssetType=typeof assetTypes[number];
export const transactions=["lease","sale","pre_leased_sale"] as const;
export type Transaction=typeof transactions[number];
export type PropertyDraft={
 projectName:string;unitNumber:string;location:string;assetType:AssetType;
 transactionType:Transaction;carpetSqFt:string;builtUpSqFt:string;
 askingRentInr:string;askingPriceInr:string;description:string;
};
export const emptyDraft:PropertyDraft={
 projectName:"",unitNumber:"",location:"",assetType:"office",transactionType:"lease",
 carpetSqFt:"",builtUpSqFt:"",askingRentInr:"",askingPriceInr:"",description:""
};
function positive(n:string){return /^\d+(?:\.\d{1,2})?$/.test(n.trim())&&Number(n)>0;}
export function validateDraft(d:PropertyDraft):string[]{
 const errors:string[]=[];
 if(d.projectName.trim().length<2)errors.push("Project/building name is required.");
 if(d.location.trim().length<2)errors.push("Locality/location is required.");
 if(!positive(d.carpetSqFt))errors.push("Provide a valid carpet area in sq.ft.");
 if(d.builtUpSqFt.trim()&&!positive(d.builtUpSqFt))errors.push("Built-up area must be positive if provided.");
 if(d.builtUpSqFt.trim()&&positive(d.builtUpSqFt)&&positive(d.carpetSqFt)&&Number(d.builtUpSqFt)<Number(d.carpetSqFt))errors.push("Built-up area cannot be smaller than carpet area without review.");
 if(d.transactionType==="lease"&&!positive(d.askingRentInr))errors.push("Provide monthly asking rent in INR.");
 if(d.transactionType!=="lease"&&!positive(d.askingPriceInr))errors.push("Provide asking sale price in INR.");
 if(d.description.length>1000)errors.push("Description must be 1,000 characters or less.");
 return errors;
}
export const portalFeatures={
 secureLogin:false,accountRegistration:false,privateInventory:false,externalSubmission:false,
 publication:false,androidTwoWaySync:false,crmWrite:false
} as const;
