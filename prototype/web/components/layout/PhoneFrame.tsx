import React from "react";

interface PhoneFrameProps {
  children: React.ReactNode;
  className?: string;
}

export default function PhoneFrame({ children, className = "" }: PhoneFrameProps) {
  return (
    <div
      className="min-h-screen w-full flex items-start justify-center py-0 sm:py-8"
      style={{ background: "#0B0D12" }}
    >
      <div
        className={`relative w-full overflow-hidden flex flex-col ${className}`}
        style={{
          maxWidth: "390px",
          minHeight: "100svh",
          background: "#0B0D12",
          boxShadow: "0 0 0 1px rgba(255,255,255,0.06), 0 32px 80px rgba(0,0,0,0.7)",
          borderRadius: "clamp(0px, (100vw - 390px) * 999, 40px)",
        }}
      >
        {children}
      </div>
    </div>
  );
}
