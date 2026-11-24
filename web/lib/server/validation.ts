import "server-only";
export type PropertyDraft={projectId:string|null;propertyCode:string|null;unitNumber:string|null;assetType:string;transactionType:string;carpetSqFt:number|null;builtupSqFt:number|null;askingRentInr:number|null;askingPriceInr:number|null;furnishing:string|null};
export function safeText(v:unknown,max=200){return typeof v==="string"?v.trim().slice(0,max):"";}
export function safeAmount(v:unknown,max=100000000000){if(v===null||v===undefined||v==="")return null;const n=Number(v);return Number.isFinite(n)&&n>=0&&n<=max?Math.round(n*100)/100:null;}
export function propertyDraft(raw:unknown):{value?:PropertyDraft;error?:string}{
 if(!raw||typeof raw!=="object"||Array.isArray(raw))return {error:"INVALID_BODY"};
 const x=raw as Record<string,unknown>;
 const assetType=safeText(x.assetType,40).toLowerCase(),transactionType=safeText(x.transactionType,30).toLowerCase();
 if(!["office","shop","showroom","warehouse","commercial_land","other"].includes(assetType))return {error:"INVALID_ASSET_TYPE"};
 if(!["lease","sale","pre_leased_sale"].includes(transactionType))return {error:"INVALID_TRANSACTION_TYPE"};
 const code=safeText(x.propertyCode,80);const projectId=safeText(x.projectId,40);
 if(projectId&&!/^[0-9a-f]{8}-[0-9a-f-]{27,36}$/i.test(projectId))return {error:"INVALID_PROJECT_ID"};
 for(const field of ["carpetSqFt","builtupSqFt","askingRentInr","askingPriceInr"]){if(x[field]!==null&&x[field]!==undefined&&x[field]!==""&&safeAmount(x[field])===null)return {error:"INVALID_"+field.toUpperCase()};}
 if(!code||code.length>80)return {error:"PROPERTY_CODE_REQUIRED"};
 const carpetSqFt=safeAmount(x.carpetSqFt);const builtupSqFt=safeAmount(x.builtupSqFt);
 if((carpetSqFt!==null&&carpetSqFt<=0)||(builtupSqFt!==null&&builtupSqFt<=0))return {error:"INVALID_AREA"};
 const value={projectId:projectId||null,propertyCode:code,unitNumber:safeText(x.unitNumber,60)||null,assetType,transactionType,carpetSqFt,builtupSqFt,askingRentInr:safeAmount(x.askingRentInr),askingPriceInr:safeAmount(x.askingPriceInr),furnishing:safeText(x.furnishing,120)||null};
 if(transactionType==="lease"&&value.askingPriceInr!==null)return {error:"LEASE_PRICE_NOT_APPLICABLE"};
 return {value};
}
export function submissionDraft(raw:unknown):{value?:Record<string,unknown>;error?:string}{
 if(!raw||typeof raw!=="object"||Array.isArray(raw))return {error:"INVALID_BODY"};
 const x=raw as Record<string,unknown>;
 const projectName=safeText(x.projectName,180),location=safeText(x.location,150),assetType=safeText(x.assetType,40).toLowerCase(),transactionType=safeText(x.transactionType,30).toLowerCase();
 if(projectName.length<3||location.length<3)return {error:"PROJECT_AND_LOCATION_REQUIRED"};
 if(!["office","shop","showroom","warehouse","commercial_land","other"].includes(assetType))return {error:"INVALID_ASSET_TYPE"};
 if(!["lease","sale","pre_leased_sale"].includes(transactionType))return {error:"INVALID_TRANSACTION"};
 for(const k of ["carpetSqFt","builtupSqFt","askingRentInr","askingPriceInr"]){if(x[k]!==null&&x[k]!==undefined&&x[k]!==""&&safeAmount(x[k])===null)return {error:"INVALID_"+k.toUpperCase()};}
 const value={projectName,location,assetType,transactionType,unitNumber:safeText(x.unitNumber,60),floor:safeText(x.floor,60),carpetSqFt:safeAmount(x.carpetSqFt),builtupSqFt:safeAmount(x.builtupSqFt),askingRentInr:safeAmount(x.askingRentInr),askingPriceInr:safeAmount(x.askingPriceInr),description:safeText(x.description,1000),ownerContactConsent:x.ownerContactConsent===true};
 if(!value.ownerContactConsent)return {error:"OWNER_AUTHORITY_CONFIRMATION_REQUIRED"};
 return {value};
}
