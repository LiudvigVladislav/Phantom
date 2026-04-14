import React from "react";
import Link from "next/link";

interface StatusBarProps {
  title?: string;
  showBack?: boolean;
  backHref?: string;
  centerSlot?: React.ReactNode;
  rightSlot?: React.ReactNode;
  relayOnline?: boolean;
}

function RelayIndicator({ online }: { online: boolean }) {
  return (
    <div className="flex items-center gap-1.5">
      <span
        className="block w-2 h-2 rounded-full"
        style={{
          background: online ? "#2FBF71" : "#F4B740",
          boxShadow: online
            ? "0 0 6px rgba(47,191,113,0.7)"
            : "0 0 6px rgba(244,183,64,0.7)",
        }}
      />
      <span
        className="text-xs font-medium tracking-wide"
        style={{ color: online ? "#2FBF71" : "#F4B740", fontSize: "11px" }}
      >
        {online ? "Relay" : "Connecting"}
      </span>
    </div>
  );
}

export default function StatusBar({
  title,
  showBack = false,
  backHref = "/",
  centerSlot,
  rightSlot,
  relayOnline = true,
}: StatusBarProps) {
  return (
    <div
      className="flex items-center justify-between px-4 shrink-0"
      style={{
        height: "56px",
        background: "#13161D",
        borderBottom: "1px solid rgba(255,255,255,0.06)",
      }}
    >
      {/* Left slot */}
      <div className="flex items-center gap-2 min-w-0" style={{ flex: 1 }}>
        {showBack && (
          <Link
            href={backHref}
            className="flex items-center justify-center w-8 h-8 rounded-full transition-colors"
            style={{ color: "#8A8FA3" }}
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6" />
            </svg>
          </Link>
        )}
        {title && (
          <span
            className="font-semibold tracking-widest uppercase"
            style={{
              color: "#F4F7FB",
              fontSize: showBack ? "14px" : "13px",
              letterSpacing: showBack ? "0.05em" : "0.2em",
            }}
          >
            {title}
          </span>
        )}
      </div>

      {/* Center slot */}
      {centerSlot && (
        <div className="flex items-center justify-center" style={{ flex: 1 }}>
          {centerSlot}
        </div>
      )}

      {/* Right slot */}
      <div className="flex items-center justify-end gap-3" style={{ flex: 1 }}>
        {rightSlot}
        <RelayIndicator online={relayOnline} />
      </div>
    </div>
  );
}
