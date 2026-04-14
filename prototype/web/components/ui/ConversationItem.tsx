import React from "react";
import Link from "next/link";
import Avatar from "./Avatar";

interface ConversationItemProps {
  name: string;
  lastMessage: string;
  time: string;
  unread?: number;
  href?: string;
  verified?: boolean;
}

export default function ConversationItem({
  name,
  lastMessage,
  time,
  unread,
  href = "/chat",
  verified = false,
}: ConversationItemProps) {
  return (
    <Link
      href={href}
      className="flex items-center gap-3 px-4 transition-colors active:opacity-80"
      style={{
        height: "72px",
        borderBottom: "1px solid rgba(255,255,255,0.06)",
        background: "#13161D",
      }}
    >
      <Avatar name={name} size="md" />

      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between gap-2 mb-0.5">
          <div className="flex items-center gap-1.5 min-w-0">
            <span
              className="font-semibold truncate"
              style={{ color: "#F4F7FB", fontSize: "15px" }}
            >
              {name}
            </span>
            {verified && (
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="#6C5CE7"
                style={{ flexShrink: 0 }}
              >
                <path d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" stroke="#6C5CE7" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
              </svg>
            )}
          </div>
          <span
            className="text-xs shrink-0"
            style={{ color: unread ? "#6C5CE7" : "#8A8FA3", fontSize: "12px" }}
          >
            {time}
          </span>
        </div>

        <div className="flex items-center justify-between gap-2">
          <span
            className="text-sm truncate"
            style={{ color: "#8A8FA3", fontSize: "13px" }}
          >
            {lastMessage}
          </span>
          {unread && unread > 0 ? (
            <span
              className="flex items-center justify-center rounded-full shrink-0 font-semibold"
              style={{
                minWidth: "20px",
                height: "20px",
                padding: "0 6px",
                background: "#6C5CE7",
                color: "#ffffff",
                fontSize: "11px",
              }}
            >
              {unread}
            </span>
          ) : null}
        </div>
      </div>
    </Link>
  );
}
