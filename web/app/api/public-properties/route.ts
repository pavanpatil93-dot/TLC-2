import {NextResponse} from "next/server";
import {readApprovedPublicListings} from "../../../lib/approved-sheets";

/** Server-side, restricted, fail-closed inventory publication endpoint. */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export async function GET() {
  try {
    const listings = await readApprovedPublicListings();
    return NextResponse.json(
      {listings,status:listings.length ? "APPROVED_LISTINGS" : "AWAITING_APPROVED_PUBLIC_INVENTORY"},
      {headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}}
    );
  } catch {
    // No raw Google API errors or private spreadsheet content exposed.
    return NextResponse.json(
      {listings:[],status:"TEMPORARILY_UNAVAILABLE"},
      {status:503,headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}}
    );
  }
}
