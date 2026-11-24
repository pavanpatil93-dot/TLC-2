import test from "node:test";
import assert from "node:assert/strict";
import {parseCsv,auditCsvFiles} from "../scripts/reconcile-inventory.mjs";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
test("CSV parser supports quotes and embedded delimiters",()=>{
 assert.deepEqual(parseCsv('Unit ID,Project Name\nA1,"Office, Tower"\nA2,"Tower ""B"""\n'),[["Unit ID","Project Name"],["A1","Office, Tower"],["A2",'Tower "B"']]);
});
test("local reconciliation flags missing and duplicate IDs without writes",()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),"tlc-audit-"));
 try{
  const file=path.join(dir,"Commercial Lease Inventory.csv");
  fs.writeFileSync(file,"Unit ID,Project Name\nOL-1,Example Plaza\nOL-1,Example Plaza\n,Missing ID\n");
  const result=auditCsvFiles([file]);
  assert.equal(result.mode,"DRY_RUN_ONLY");assert.equal(result.mutated,false);
  assert.equal(result.duplicateKeys,1);assert.equal(result.totalRows,3);
  assert.equal(result.files[0].missingId,1);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test("malformed quoted CSV is rejected",()=>assert.throws(()=>parseCsv('Unit ID,Name\nX,"Unclosed'),/Malformed CSV/));
