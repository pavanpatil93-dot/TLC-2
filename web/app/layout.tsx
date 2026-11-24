import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "The Leasing Company | Pune Property Specialists", description: "Explore commercial offices, shops, showrooms, leasing, outright sale and pre-leased investments in Pune with The Leasing Company.", robots: { index: false, follow: false } };
export default function RootLayout({ children }: Readonly<{children: React.ReactNode}>) { return <html lang="en"><body>{children}</body></html>; }
