#!/usr/bin/env node
/** Batch 08 local-only, read-only CSV reconciliation. Does NOT query or change TLC Sheets/DB. */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import {fileURLToPath} from "node:url";
export function parseCsv(input){
 const rows=[];let row=[],cell="",quoted=false;
 for(let i=0;i<input.length;i++){
  const c=input[i];
  if(quoted){if(c==='"'&&input[i+1]==='"'){cell+='"';i++;}else if(c==='"'){quoted=false;}else{cell+=c;}}
  else if(c==='"'){quoted=true;}
  else if(c===','){row.push(cell);cell="";}
  else if(c==="\n"||c==="\r"){if(c==="\r"&&input[i+1]==="\n")i++;row.push(cell);if(row.some(x=>x!==""))rows.push(row);row=[];cell="";}
  else cell+=c;
 }
 if(quoted)throw Error("Malformed CSV: unclosed quotation mark");
 if(row.length||cell){row.push(cell);rows.push(row);}
 return rows;
}
export function auditCsvFiles(files){
 const seen=new Map();const reports=[];let total=0;let duplicates=0;
 for(const source of files){
  const bytes=fs.readFileSync(source);
  const contents=bytes.toString("utf8").replace(/^\uFEFF/,"");
  const csv=parseCsv(contents);const header=(csv[0]||[]).map(x=>x.trim());
  const idIndex=header.findIndex(x=>["Unit ID","Property ID","Property Code","Unit Code"].includes(x));
  const projectIndex=header.findIndex(x=>["Project / Building Name","Project Name","Society / Project Name"].includes(x));
  if(idIndex<0)throw Error("Missing Unit ID / Property Code in "+path.basename(source));
  const report={file:path.basename(source),sha256:crypto.createHash("sha256").update(bytes).digest("hex"),records:0,missingId:0,missingProject:0,duplicateIds:0};
  const tab=path.basename(source).replace(/\.csv$/i,"");
  for(const row of csv.slice(1)){
   report.records++;total++;
   const id=(row[idIndex]||"").trim();
   if(!id){report.missingId++;continue;}
   if(projectIndex>=0&&!(row[projectIndex]||"").trim())report.missingProject++;
   const key=tab.toLowerCase()+"::"+id.toLowerCase();
   if(seen.has(key)){report.duplicateIds++;duplicates++;}
   else seen.set(key,true);
  }
  reports.push(report);
 }
 return {mode:"DRY_RUN_ONLY",mutated:false,files:reports,totalRows:total,duplicateKeys:duplicates,warning:"Cross-tab duplicate physical units require project/unit/transaction matching and manual reconciliation. Do not import automatically."};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const args=process.argv.slice(2);
 if(!args.length){console.error("Usage: node scripts/reconcile-inventory.mjs <export1.csv> [export2.csv...]");process.exitCode=2;}
 else {try{console.log(JSON.stringify(auditCsvFiles(args),null,2));}catch(e){console.error("RECONCILIATION_FAILED:",e instanceof Error?e.message:"Unknown input error");process.exitCode=1;}}
}
