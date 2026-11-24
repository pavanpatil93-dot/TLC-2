import {NextResponse} from "next/server";
/** Fail-closed public endpoint. No Google Sheet or CRM API credentials are sent to browsers. */
export async function GET(){return NextResponse.json({listings:[],status:"AWAITING_APPROVED_PUBLIC_INVENTORY"},{headers:{"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}})}
