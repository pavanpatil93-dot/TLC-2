import test from "node:test";import assert from "node:assert/strict";import {audit} from "./reconcile-inventory.mjs";
test("count records and duplicates without printing any PII",()=>{assert.deepEqual(audit("Unit ID,Project Name,Owner Phone\nOL-1,Office A,000\nOL-1,Office A,111\nOL-2,Office B,222\n,Office C,333\n"),{rows:4,missingId:1,duplicateIdGroups:1,completeKeyPairs:3});});
test("quoted commas and blank CSV are handled",()=>{assert.equal(audit('Unit ID,Project Name\n"OL-1","Complex, East"\n').rows,1);assert.equal(audit("").rows,0);});
