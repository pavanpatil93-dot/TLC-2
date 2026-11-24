import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "The Leasing Company | Pune Property Specialists", description: "Discover commercial and residential property opportunities in Pune. Request a property shortlist or site visit with The Leasing Company.", robots: { index: false, follow: false } };
export default function RootLayout({ children }: Readonly<{children: React.ReactNode}>) { return <html lang="en"><body>{children}</body></html>; }
