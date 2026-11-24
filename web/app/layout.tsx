import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "The Leasing Company | Pune Commercial Properties", description: "Search commercial offices, shops, showrooms, outright sales and pre-leased investments across Pune with The Leasing Company.", robots: { index: false, follow: false } };
export default function RootLayout({ children }: Readonly<{children: React.ReactNode}>) { return <html lang="en"><body>{children}</body></html>; }
