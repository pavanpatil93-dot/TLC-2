import "server-only";
import {google} from "googleapis";
import {deduplicate, INVENTORY_TABS, toPublicListings, type PublicListing} from "./public-inventory";

import {Pool} from "pg";
import {approvalAllows,type PublicationApproval} from "./publication-approval";
let reader:Pool|undefined;
const SHEET_ID = process.env.TLC_MASTER_SHEET_ID;

/**
 * Google Cloud service account must be granted Viewer access to the TLC Sheet.
 * Use environment variables only, never embed credentials or Sheet payloads into client code.
 * Permission to read does NOT authorize publication of raw records.
 */
export async function readApprovedPublicListings(): Promise<PublicListing[]> {
  if(process.env.TLC_MASTER_PUBLICATION_MODE!=="registry")return [];
  const url=process.env.TLC_PUBLICATION_READER_URL;
  if(!url)throw new Error("PUBLICATION_READER_NOT_CONFIGURED");
  reader??=new Pool({connectionString:url,max:2,connectionTimeoutMillis:2500,statement_timeout:4000});
  const read=async()=> (await reader!.query<PublicationApproval>("SELECT property_key,payload_sha256,expires_at::text,revoked_at::text FROM tlc.master_publication_approvals WHERE revoked_at IS NULL AND expires_at>now()")).rows;
  const initial=await read();
  const listings=await readPublicationPreview(new Set(initial.map(a=>a.property_key)));
  const current=new Map((await read()).map(a=>[a.property_key,a]));
  return listings.filter(p=>approvalAllows(p,current.get(p.id)));
}

// Server-only; callers must authenticate before supplying preview keys.
export async function readPublicationPreview(approved: ReadonlySet<string>): Promise<PublicListing[]> {
  if (approved.size === 0) return [];
  const email = process.env.TLC_GOOGLE_SERVICE_ACCOUNT_EMAIL;
  const key = process.env.TLC_GOOGLE_PRIVATE_KEY?.replace(/\\n/g, "\n");
  if (!email || !key || !SHEET_ID) return [];
  const auth = new google.auth.JWT({
    email, key, scopes: ["https://www.googleapis.com/auth/spreadsheets.readonly"]
  });
  const sheets = google.sheets({version:"v4",auth});
  const listings: PublicListing[] = [];
  for (const tab of INVENTORY_TABS) {
    // Tab can be treated as public only when each record passes explicit approval
    // AND verification AND availability gates.
    const range = "'" + tab.replaceAll("'", "''") + "'!A1:BN";
    const {data} = await sheets.spreadsheets.values.get({
      spreadsheetId:SHEET_ID,range,valueRenderOption:"UNFORMATTED_VALUE"
    });
    listings.push(...toPublicListings(tab,(data.values ?? []).map(row => row.map(v => String(v ?? ""))),approved));
  }
  return deduplicate(listings);
}
