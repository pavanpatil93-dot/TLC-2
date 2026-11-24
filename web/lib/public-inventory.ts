/**
 * Safe public inventory mapping for TLC Master Sheet.
 * No owner, broker, source, remarks, phone, WhatsApp Pitch or CRM fields are exported.
 * Publication is always explicit, and verified/available are additionally required.
 */
export type PublicListing = {
  id: string;
  title: string;
  location: string;
  type: "lease" | "resale" | "residential";
  areaSqFt: number | null;
  priceInr: number | null;
  transaction: "lease" | "sale";
  description: string;
  projectName?: string;
  assetType?: string;
  floor?: string;
  builtUpSqFt?: number | null;
  furnishing?: string;
  currentRentInr?: number | null;
  isPreLeased?: boolean;
  yieldPct?: number | null;
  isUnderConstruction?: boolean;
  constructionStatus?: string;
  targetPossessionDate?: string;
};

export const INVENTORY_TABS = [
  "Commercial Lease Inventory",
  "Commercial Resale Inventory",
  "Residential Under Construction",
  "Residential Resale",
  "Residential Rentals"
] as const;
export type InventoryTab = (typeof INVENTORY_TABS)[number];

type RawRow = Record<string, string>;
const text = (row: RawRow, key: string): string => (row[key] ?? "").trim();
const first = (row: RawRow, keys: string[]): string =>
  keys.map((k) => text(row, k)).find(Boolean) ?? "";

function amount(s: string): number | null {
  // Do not turn ambiguous monetary expressions into fake numbers.
  const value = s.replaceAll(",", "").replaceAll("₹", "").trim();
  if (!/^\d+(?:\.\d+)?$/.test(value)) return null;
  const number = Number(value);
  return Number.isSafeInteger(number) || (Number.isFinite(number) && number > 0)
    ? number > 0 ? number : null
    : null;
}

function eligible(row: RawRow): boolean {
  const status = text(row, "Inventory Status").toLowerCase();
  const verification = text(row, "Verification Status").toLowerCase();
  return status === "available" &&
    (verification === "verified" || verification === "verified confirmed");
}

export function toPublicListings(
  sheet: InventoryTab,
  values: string[][],
  approvedKeys: ReadonlySet<string>
): PublicListing[] {
  const [headers, ...data] = values;
  if (!headers?.includes("Unit ID") ||
      !headers.includes("Inventory Status") ||
      !headers.includes("Verification Status")) return [];
  const output: PublicListing[] = [];
  for (const cells of data) {
    const row = Object.fromEntries(headers.map((header, index) => [header.trim(), cells[index] ?? ""])) as RawRow;
    const id = text(row, "Unit ID");
    if (!id || !approvedKeys.has(sheet + ":" + id) || !eligible(row)) continue;
    const project = first(row, ["Project / Building Name", "Project Name", "Society / Project Name"]);
    const location = first(row, ["Micro-Market", "Location"]);
    if (!project || !location) continue;
    const title = project; // Exact unit identifiers are internal; public enquiries use the listing reference.
    const areaSqFt = amount(first(row, ["Total Carpet Area (Sq Ft)", "Carpet Area (Sq Ft)"]));
    const isLease = sheet === "Commercial Lease Inventory" || sheet === "Residential Rentals";
    const type: PublicListing["type"] = sheet === "Commercial Lease Inventory" ? "lease" :
      sheet === "Commercial Resale Inventory" ? "resale" : "residential";
    const priceInr = amount(first(row, isLease ?
      ["Asking Rent / Month (₹)"] :
      ["Base / Agreement Value (₹)", "Agreement Value (₹)", "Asking Price (₹)"]));
    const configuration = first(row, ["Configuration", "Asset Type"]);
    const condition = first(row, ["Condition / Fit-out", "Condition & Furnishing"]);
    const description = [configuration, condition].filter(Boolean).join(" · ").slice(0, 160);
    const builtUpSqFt = amount(first(row, ["Total Built-Up Area (Sq Ft)"]));
    const floor = first(row, ["Floor Number"]).slice(0, 40);
    const currentRentInr = sheet === "Commercial Resale Inventory" ? amount(text(row, "Current Monthly Rent (₹)")) : null;
    const isPreLeased = sheet === "Commercial Resale Inventory" && /^(yes|pre[ -]?leased|leased)$/i.test(text(row, "Pre-Leased Status"));
    const rawYield = text(row, "Current ROI / Yield (%)").replace("%","").trim();
    const parsedYield = Number(rawYield);
    const yieldPct = isPreLeased && rawYield && Number.isFinite(parsedYield) && parsedYield > 0 && parsedYield < 100 ? parsedYield : null;
    const constructionStatus = sheet === "Residential Under Construction" ? "Under Construction" : sheet === "Commercial Resale Inventory" ? text(row, "OC / Construction Status").slice(0,80) : "";
    const isUnderConstruction = /^(under[ -]?construction|construction[ -]?in[ -]?progress|under development|work in progress|ongoing construction)$/i.test(constructionStatus);
    const targetPossessionDate = (sheet === "Commercial Resale Inventory" || sheet === "Residential Under Construction") ? text(row, "Target Possession Date").slice(0,50) : "";
    output.push({id:sheet + ":" + id,title,location,type,areaSqFt,priceInr,
      transaction:isLease ? "lease" : "sale",description,projectName:project,assetType:configuration,floor,builtUpSqFt,furnishing:condition,currentRentInr,isPreLeased,yieldPct,isUnderConstruction,constructionStatus,targetPossessionDate});
  }
  return output;
}

export function approvedPublicationKeys(value: string | undefined): ReadonlySet<string> {
  if (!value) return new Set();
  // Example: ["Commercial Lease Inventory:COM-123", "Residential Resale:RES-125"]
  try {
    const entries: unknown = JSON.parse(value);
    if (!Array.isArray(entries)) return new Set();
    return new Set(entries.filter((v):v is string => typeof v === "string" &&
      INVENTORY_TABS.some((tab) => v.startsWith(tab + ":"))));
  } catch { return new Set(); }
}

export function deduplicate(listings: PublicListing[]): PublicListing[] {
  const seen = new Set<string>();
  return listings.filter((item) => {
    // A repeated physical unit is not shown twice, even with different source IDs.
    const key = item.id; // Distinct approved units in the same project must remain distinct.
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
