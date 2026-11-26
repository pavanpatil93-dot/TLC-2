import test from "node:test";
import assert from "node:assert/strict";
import ts from "typescript";
import {readFileSync} from "node:fs";
const source=readFileSync(new URL("../lib/public-inventory.ts",import.meta.url),"utf8");
const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const {toPublicListings,approvedPublicationKeys}=await import("data:text/javascript;base64,"+Buffer.from(js).toString("base64"));
const specs=[
["Commercial Lease Inventory","Project / Building Name","Asset Type","Total Carpet Area (Sq Ft)","Asking Rent / Month (₹)","lease"],
["Commercial Resale Inventory","Project / Building Name","Asset Type","Total Carpet Area (Sq Ft)","Base / Agreement Value (₹)","sale"],
["Residential Under Construction","Project Name","Configuration","Carpet Area (Sq Ft)","Agreement Value (₹)","sale"],
["Residential Resale","Society / Project Name","Configuration","Carpet Area (Sq Ft)","Asking Price (₹)","sale"],
["Residential Rentals","Society / Project Name","Configuration","Carpet Area (Sq Ft)","Asking Rent / Month (₹)","lease"]];
for(const [tab,project,asset,area,price,tx] of specs){
 const h=["Unit ID",project,"Location",asset,area,price,"Inventory Status","Verification Status","Duplicate Check","Owner / Developer Phone","Remarks","",""];
 const r=["TEST-1","Example Building","Example Area","Office","500","45000","Available","Confirmed / Verified","Clear","PRIVATE_PHONE","PRIVATE_REMARK","",""];
 const approved=approvedPublicationKeys(JSON.stringify([tab+":TEST-1"]));
 test(tab+" maps only approved public fields",()=>{const [p]=toPublicListings(tab,[h,r],approved);assert.equal(p.priceInr,45000);assert.equal(p.areaSqFt,500);assert.equal(p.transaction,tx);assert.equal(p.title,"Example Building");assert.doesNotMatch(JSON.stringify(p),/PRIVATE_|floor|owner|remarks/i);});
 test(tab+" fails closed",()=>{assert.deepEqual(toPublicListings(tab,[h,r],new Set()),[]);assert.deepEqual(toPublicListings(tab,[h,r,r],approved),[]);for(const [i,v] of [[6,"Archived"],[7,"Pending"],[8,"Duplicate"],[1,"Call 9999999999"]]){const bad=[...r];bad[i]=v;assert.deepEqual(toPublicListings(tab,[h,bad],approved),[]);}assert.deepEqual(toPublicListings(tab,[h.slice(1),r.slice(1)],approved),[]);});
}

test("residential primary carpet and construction fields",()=>{
 const tab="Residential Under Construction";
 const h=["Unit ID","Project Name","Location","Configuration","Carpet Area (Sq Ft)","Agreement Value (₹)","Inventory Status","Verification Status","Duplicate Check","Total Carpet Area (Sq Ft)","Construction Stage","Target Possession Date"];
 const r=["TEST-2","Example","Pune","2 BHK","800","8000000","Available","Confirmed / Verified","Clear","850","Foundation","2028-12-01"];
 const [p]=toPublicListings(tab,[h,r],new Set([tab+":TEST-2"]));
 assert.equal(p.areaSqFt,800);assert.equal(p.constructionStatus,"Foundation");assert.equal(p.targetPossessionDate,"2028-12-01");
 const stats={rows:0,eligible:0,rejected:{},schemaValid:false};
 const bad=[...r];bad[0]="TEST-3";bad[7]="Pending";
 assert.deepEqual(toPublicListings(tab,[h,r,bad],new Set(),stats),[]);
 assert.deepEqual(stats,{rows:2,eligible:1,rejected:{verification:1},schemaValid:true});
});
