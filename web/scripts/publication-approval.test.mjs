import test from "node:test";
import assert from "node:assert/strict";
import ts from "typescript";
import {readFileSync} from "node:fs";
const source=readFileSync(new URL("../lib/publication-approval.ts",import.meta.url),"utf8");
const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const {approvalAllows,publicPayloadDigest}=await import("data:text/javascript;base64,"+Buffer.from(js).toString("base64"));
const listing={id:"Commercial Inventory:TEST-1",title:"Example",priceInr:50000};
const now=Date.parse("2026-11-25T00:00:00Z");
const approval={property_key:listing.id,payload_sha256:publicPayloadDigest(listing),expires_at:"2026-12-01T00:00:00Z",revoked_at:null};
test("exact reviewed payload and unexpired approval required",()=>{
 assert.equal(approvalAllows(listing,approval,now),true);
 assert.equal(approvalAllows({...listing,priceInr:60000},approval,now),false);
 assert.equal(approvalAllows(listing,undefined,now),false);
 assert.equal(approvalAllows(listing,{...approval,revoked_at:"2026-11-24"},now),false);
 assert.equal(approvalAllows(listing,{...approval,expires_at:"invalid"},now),false);
 assert.equal(approvalAllows(listing,approval,Date.parse(approval.expires_at)),false);
});
