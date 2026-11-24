import "server-only";
import {google} from "googleapis";
import {approvedPublicationKeys, deduplicate, INVENTORY_TABS, toPublicListings, type PublicListing} from "./public-inventory";

const SHEET_ID = "1GPHd1AB89yxf6CqvmGKvR68kVUxZ26Eh5xSWD4HE5-0";

/**
 * Google Cloud service account must be granted Viewer access to the TLC Sheet.
 * Use environment variables only, never embed credentials or Sheet payloads into client code.
 * Permission to read does NOT authorize publication of raw records.
 */
export async function readApprovedPublicListings(): Promise<PublicListing[]> {
  const approved = approvedPublicationKeys(process.env.TLC_PUBLIC_APPROVED_IDS);
  if (approved.size === 0) return [];
  const email = process.env.TLC_GOOGLE_SERVICE_ACCOUNT_EMAIL;
  const key = process.env.TLC_GOOGLE_PRIVATE_KEY?.replace(/\\n/g, "\n");
  if (!email || !key) return [];
  const auth = new google.auth.JWT({
    email, key, scopes: ["https://www.googleapis.com/auth/spreadsheets.readonly"]
  });
  const sheets = google.sheets({version:"v4",auth});
  const listings: PublicListing[] = [];
  for (const tab of INVENTORY_TABS) {
    // Tab can be treated as public only when each record passes explicit approval
    // AND verification AND availability gates.
    const range = "'" + tab.replaceAll("'", "''") + "'!A1:AZ10000";
    const {data} = await sheets.spreadsheets.values.get({
      spreadsheetId:SHEET_ID,range,valueRenderOption:"FORMATTED_VALUE"
    });
    listings.push(...toPublicListings(tab,(data.values ?? []).map(row => row.map(v => String(v ?? ""))),approved));
  }
  return deduplicate(listings).slice(0,500);
}
