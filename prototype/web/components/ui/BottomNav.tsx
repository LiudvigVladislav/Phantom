"use client";
import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";

type TabId = "chats" | "contacts" | "settings";

interface BottomNavProps {
  active: TabId;
  unreadChats?: number;
}

const tabs: { id: TabId; href: string; label: string; icon: (active: boolean) => React.ReactNode }[] = [
  {
    id: "chats",
    href: "/",
    label: "Chats",
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" strokeWidth={active ? "1.8" : "1.5"}
        strokeLinecap="round" strokeLinejoin="round">
        {/* Chat bubble with tail */}
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
  {
    id: "contacts",
    href: "/connect",
    label: "Add",
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" strokeWidth={active ? "1.8" : "1.5"}
        strokeLinecap="round" strokeLinejoin="round">
        {/* Person + plus */}
        <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <line x1="19" y1="8" x2="19" y2="14" />
        <line x1="16" y1="11" x2="22" y2="11" />
      </svg>
    ),
  },
  {
    id: "settings",
    href: "/settings",
    label: "Profile",
    icon: (active) => (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" strokeWidth={active ? "1.8" : "1.5"}
        strokeLinecap="round" strokeLinejoin="round">
        {/* Person silhouette — clearly "profile/account" */}
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
        <circle cx="12" cy="7" r="4" />
      </svg>
    ),
  },
];

export default function BottomNav({ active, unreadChats = 0 }: BottomNavProps) {
  return (
    <div
      className="flex items-stretch shrink-0"
      style={{
        height: "60px",
        background: "#0F1318",
        borderTop: "1px solid rgba(255,255,255,0.06)",
      }}
    >
      {tabs.map((tab) => {
        const isActive = tab.id === active;
        const showBadge = tab.id === "chats" && unreadChats > 0;

        return (
          <Link
            key={tab.id}
            href={tab.href}
            className="flex flex-col items-center justify-center gap-1 transition-all"
            style={{
              flex: 1,
              color: isActive ? "#00D4FF" : "#6B8A9A",
              opacity: isActive ? 1 : 0.55,
              position: "relative",
              textDecoration: "none",
            }}
          >
            {/* Active indicator — thin line on top */}
            {isActive && (
              <span
                style={{
                  position: "absolute",
                  top: 0,
                  left: "50%",
                  transform: "translateX(-50%)",
                  width: 28,
                  height: 2,
                  background: "#00D4FF",
                  borderRadius: "0 0 2px 2px",
                  boxShadow: "0 0 10px rgba(0,212,255,0.7)",
                }}
              />
            )}

            {/* Icon wrapper with badge */}
            <div style={{ position: "relative" }}>
              {tab.icon(isActive)}

              {/* Unread badge */}
              {showBadge && (
                <span
                  style={{
                    position: "absolute",
                    top: -4,
                    right: -6,
                    minWidth: 16,
                    height: 16,
                    borderRadius: 8,
                    background: "#00D4FF",
                    boxShadow: "0 0 8px rgba(0,212,255,0.6)",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: "9px",
                    fontWeight: 600,
                    color: "#fff",
                    letterSpacing: 0,
                    padding: "0 3px",
                    border: "1.5px solid #0F1318",
                  }}
                >
                  {unreadChats > 99 ? "99+" : unreadChats}
                </span>
              )}
            </div>

            {/* Label */}
            <span
              style={{
                fontSize: "9px",
                letterSpacing: "1px",
                textTransform: "uppercase",
                fontWeight: isActive ? 500 : 400,
                color: isActive ? "#00D4FF" : "#6B8A9A",
              }}
            >
              {tab.label}
            </span>
          </Link>
        );
      })}
    </div>
  );
}
