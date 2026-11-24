import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {resolve,dirname} from "node:path";

// These are guardrail tests, NOT evidence of real authentication or deployed services.
// A missing activation dependency must never silently turn a preview into a writable CRM.
const webRoot=resolve(dirname(fileURLToPath(import.meta.url)),"..");
function source(path){return readFileSync(resolve(webRoot,path),"utf8");}
const featureRoute="app/api/v1/system/features/route.ts";

test("batch 04-09: preview does not claim unverified live capabilities",()=>{
  const code=source(featureRoute);
  for(const key of ["login","ownerPosting","brokerPosting","employeeWrite","androidSync","crmSubmission"]){
    assert.match(code,new RegExp(key+"\\s*:\\s*false"),key+" must remain disabled until verified activation");
  }
});

test("batch 09: public enquiry intake fails closed before consent and rate limiting",()=>{
  const code=source("app/api/v1/enquiries/route.ts");
  assert.match(code,/ENQUIRY_SUBMISSION_NOT_ACTIVATED/);
  assert.match(code,/status\s*:\s*503/);
});

test("batch 03: database access is server-only and has a configuration guard",()=>{
  const code=source("lib/server/database.ts");
  assert.match(code,/import\s+["']server-only["']/);
  assert.match(code,/DATABASE_NOT_CONFIGURED/);
  assert.match(code,/connectionTimeoutMillis/);
});

test("batch 06: preview property form never claims to publish a listing",()=>{
  const code=source("app/portal/new-property/page.tsx");
  assert.match(code,/No record will be posted or published from this preview/);
});

test("batch 10: staging has a documented production release gate",()=>{
  const checklist=source("docs/LAUNCH_CHECKLIST.md");
  for(const gate of ["permission","privacy","owner-authorized","rollback","Android","authentication"]){
    assert.ok(checklist.toLowerCase().includes(gate.toLowerCase()),"missing launch gate: "+gate);
  }
});
