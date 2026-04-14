import React from "react";
import Link from "next/link";

type TabId = "chats" | "contacts" | "settings";

interface BottomNavProps {
  active: TabId;
}

const tabs: { id: TabId; label: string; href: string; icon: React.ReactNode }[] = [
  {
    id: "chats",
    label: "Chats",
    href: "/",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
    ),
  },
  {
    id: "contacts",
    label: "Contacts",
    href: "/connect",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
        <circle cx="9" cy="7" r="4" />
        <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
        <path d="M16 3.13a4 4 0 0 1 0 7.75" />
      </svg>
    ),
  },
  {
    id: "settings",
    label: "Settings",
    href: "/settings",
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3" />
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
      </svg>
    ),
  },
];

export default function BottomNav({ active }: BottomNavProps) {
  return (
    <div
      className="flex items-stretch shrink-0"
      style={{
        height: "60px",
        background: "#13161D",
        borderTop: "1px solid rgba(255,255,255,0.06)",
      }}
    >
      {tabs.map((tab) => {
        const isActive = tab.id === active;
        return (
          <Link
            key={tab.id}
            href={tab.href}
            className="relative flex flex-col items-center justify-center gap-1 transition-all"
            style={{
              flex: 1,
              color: isActive ? "#6C5CE7" : "#8A8FA3",
            }}
          >
            <span
              className="transition-transform"
              style={{
                transform: isActive ? "translateY(-1px)" : "none",
                opacity: isActive ? 1 : 0.7,
              }}
            >
              {tab.icon}
            </span>
            <span
              className="font-medium tracking-wide"
              style={{
                fontSize: "10px",
                letterSpacing: "0.06em",
                color: isActive ? "#6C5CE7" : "#8A8FA3",
              }}
            >
              {tab.label}
            </span>
            {isActive && (
              <span
                className="absolute bottom-1 w-1 h-1 rounded-full"
                style={{ background: "#6C5CE7" }}
              />
            )}
          </Link>
        );
      })}
    </div>
  );
}
