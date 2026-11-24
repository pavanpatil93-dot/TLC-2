#!/usr/bin/env node
/* Batch 08: READ-ONLY migration audit. Never prints owner/client data. Never writes to Sheets or PostgreSQL. */
import {readFileSync} from "node:fs";
function rows(csv){
 const result=[];let field="",row=[],quoted=false;
 for(let i=0;i<csv.length;i++){const ch=csv[i];if(ch==='"'){if(quoted&&csv[i+1]==='"'){field+='"';i++;}else quoted=!quoted;}
 else if(ch===","&&!quoted){row.push(field);field="";}
 else if((ch==="\n"||ch==="\r")&&!quoted){if(ch==="\r"&&csv[i+1]==="\n")i++;row.push(field);if(row.some(x=>x.trim()))result.push(row);row=[];field="";}
 else field+=ch;
 }
 row.push(field);if(row.some(x=>x.trim()))result.push(row);
 return result;
}
export function audit(csv){
 const all=rows(csv);if(!all.length)return {rows:0,missingId:0,duplicateIdGroups:0,completeKeyPairs:0};
 const headers=all[0].map(x=>x.trim().toLowerCase());
 const index=["unit id","property code","property id","id"].map(x=>headers.indexOf(x)).find(i=>i>=0)??-1;
 const project=headers.findIndex(x=>["project / building name","project name","building name"].includes(x));
 const seen=new Map();let missingId=0,completeKeyPairs=0;
 for(const row of all.slice(1)){const id=index<0?"":String(row[index]||"").trim();if(!id){missingId++;continue;}seen.set(id,(seen.get(id)||0)+1);if(project>=0&&String(row[project]||"").trim())completeKeyPairs++;}
 return {rows:all.length-1,missingId,duplicateIdGroups:[...seen.values()].filter(x=>x>1).length,completeKeyPairs};
}
if(process.argv[1]?.endsWith("reconcile-inventory.mjs")){
 const files=process.argv.slice(2);if(!files.length){process.stderr.write("Usage: node reconcile-inventory.mjs <staging-csv> [more-csv-files]\n");process.exitCode=2;}
 else{for(const file of files){try{const input=readFileSync(file,"utf8");if(input.length>50_000_000)throw Error("CSV too large");process.stdout.write(JSON.stringify({file:file.split("/").at(-1),...audit(input)})+"\n");}catch{process.stderr.write("Unable to read/validate one export. No data changed.\n");process.exitCode=1;}}}
}
