import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: "PHANTOM",
  description: "Your identity. Your keys.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${inter.variable} h-full`}>
      <body
        className="h-full antialiased"
        style={{ background: "#0B0D12", fontFamily: "'Inter', sans-serif" }}
      >
        {children}
      </body>
    </html>
  );
}
