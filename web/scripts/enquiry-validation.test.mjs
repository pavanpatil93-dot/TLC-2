import test from "node:test";
import assert from "node:assert/strict";
import ts from "typescript";
import {readFileSync} from "node:fs";
const js=ts.transpileModule(readFileSync(new URL("../lib/enquiry-validation.ts",import.meta.url),"utf8"),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const {validateEnquiry}=await import("data:text/javascript;base64,"+Buffer.from(js).toString("base64"));
const valid={name:"Synthetic test",phone:"+12025550101",requirement:"Synthetic office requirement",consent:true,captchaToken:"synthetic-token"};
test("valid synthetic enquiry",()=>assert.equal(validateEnquiry(valid).phone,valid.phone));
test("reject malformed or nonconsenting enquiries",()=>{for(const x of [null,[],{}, {...valid,consent:false},{...valid,phone:"123"},{...valid,name:"x"},{...valid,requirement:"x"},{...valid,captchaToken:""},{...valid,requirement:"x".repeat(2001)}])assert.equal(validateEnquiry(x),null);});
