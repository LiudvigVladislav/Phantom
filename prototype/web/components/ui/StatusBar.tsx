import React from "react";
import Link from "next/link";

interface StatusBarProps {
  title?: string;
  showBack?: boolean;
  backHref?: string;
  rightSlot?: React.ReactNode;
  relayOnline?: boolean;
  relayDirect?: boolean;
}

function RelayBadge({ online, direct }: { online: boolean; direct?: boolean }) {
  const label = direct ? "DIRECT" : online ? "RELAY" : "OFFLINE";
  const color = online ? "#2FBF71" : "#F4B740";

  return (
    <div className="flex items-center gap-1.5">
      <span
        className="animate-relay-pulse"
        style={{
          display: "block",
          width: 5,
          height: 5,
          borderRadius: "50%",
          background: color,
          boxShadow: `0 0 5px ${color}99`,
          flexShrink: 0,
        }}
      />
      <span
        style={{
          color,
          fontSize: "10px",
          letterSpacing: "1.5px",
          fontWeight: 500,
        }}
      >
        {label}
      </span>
    </div>
  );
}

export default function StatusBar({
  title,
  showBack = false,
  backHref = "/",
  rightSlot,
  relayOnline = true,
  relayDirect = false,
}: StatusBarProps) {
  return (
    <div
      className="flex items-center justify-between px-4 shrink-0"
      style={{
        height: "52px",
        background: "#0F1318",
        borderBottom: "1px solid rgba(255,255,255,0.06)",
      }}
    >
      {/* Left slot */}
      <div className="flex items-center gap-2 min-w-0" style={{ flex: 1 }}>
        {showBack && (
          <Link
            href={backHref}
            className="flex items-center justify-center shrink-0 transition-opacity hover:opacity-70"
            style={{ color: "#6B8A9A", marginRight: 4 }}
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="15 18 9 12 15 6" />
            </svg>
          </Link>
        )}
        {title && (
          <span
            style={{
              color: "#E8F4F8",
              fontSize: showBack ? "15px" : "13px",
              fontWeight: showBack ? 300 : 400,
              letterSpacing: showBack ? "0.5px" : "2px",
              textTransform: showBack ? "none" : "uppercase",
            }}
          >
            {title}
          </span>
        )}
      </div>

      {/* Right slot */}
      <div className="flex items-center gap-3 justify-end" style={{ flex: 1 }}>
        {rightSlot}
        <RelayBadge online={relayOnline} direct={relayDirect} />
      </div>
    </div>
  );
}
