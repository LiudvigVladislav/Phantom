import type { Metadata } from "next";
import { DM_Sans } from "next/font/google";
import "./globals.css";

const dmSans = DM_Sans({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-dm-sans",
  weight: ["200", "300", "400", "500"],
});

export const metadata: Metadata = {
  title: "PHANTOM",
  description: "Your presence, known to no one.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${dmSans.variable} h-full`}>
      <body
        className="h-full antialiased"
        style={{ background: "#0B0D12" }}
      >
        {children}
      </body>
    </html>
  );
}
