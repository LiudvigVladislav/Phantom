import React from "react";
import Link from "next/link";

interface ConversationItemProps {
  name: string;
  subline: string;
  time: string;
  unread?: number;
  accentColor?: string;
  href?: string;
}

export default function ConversationItem({
  name,
  subline,
  time,
  unread = 0,
  accentColor = "#00D4FF",
  href = "/chat",
}: ConversationItemProps) {
  return (
    <Link
      href={href}
      className="phantom-row flex items-stretch"
      style={{
        borderBottom: "1px solid rgba(255,255,255,0.04)",
        background: "transparent",
        textDecoration: "none",
      }}
    >
      {/* Left color strip */}
      <div
        style={{
          width: 3,
          background: accentColor,
          flexShrink: 0,
          opacity: unread > 0 ? 1 : 0.28,
        }}
      />

      {/* Content */}
      <div className="flex flex-col justify-center flex-1 min-w-0 py-4 pl-4 pr-4">
        <div className="flex items-center justify-between gap-2">
          {/* Name + unread dot */}
          <div className="flex items-center gap-2 min-w-0">
            <span
              style={{
                color: "#E8F4F8",
                fontSize: "15px",
                fontWeight: unread > 0 ? 400 : 300,
                letterSpacing: "0.4px",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {name}
            </span>
            {unread > 0 && (
              <span
                style={{
                  width: 6,
                  height: 6,
                  borderRadius: "50%",
                  background: accentColor,
                  flexShrink: 0,
                  boxShadow: `0 0 5px ${accentColor}BB`,
                }}
              />
            )}
          </div>

          {/* Time */}
          <span
            style={{
              color: "#6B8A9A",
              fontSize: "10px",
              letterSpacing: "1.2px",
              textTransform: "uppercase",
              flexShrink: 0,
            }}
          >
            {time}
          </span>
        </div>

        {/* Subline */}
        <span
          style={{
            color: "#6B8A9A",
            fontSize: "11px",
            letterSpacing: "0.3px",
            marginTop: "4px",
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {subline}
        </span>
      </div>
    </Link>
  );
}
