import type { Metadata } from "next";
import "./globals.css";

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
    <html lang="en" className="h-full">
      <body
        className="h-full antialiased"
        style={{ background: "#0B0D12" }}
      >
        {children}
      </body>
    </html>
  );
}
