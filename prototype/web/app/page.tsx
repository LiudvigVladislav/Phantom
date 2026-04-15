import PhoneFrame from "@/components/layout/PhoneFrame";
import BottomNav from "@/components/ui/BottomNav";
import ConversationItem from "@/components/ui/ConversationItem";
import Link from "next/link";

const conversations = [
  {
    name: "Aria Chen",
    subline: "KEY EXCHANGE CONFIRMED",
    time: "now",
    unread: 3,
    accentColor: "#00D4FF",
  },
  {
    name: "Marcus Yael",
    subline: "send me the backup phrase",
    time: "2m",
    unread: 0,
    accentColor: "#2FBF71",
  },
  {
    name: "Encrypted Group",
    subline: "you: see you on the other side",
    time: "14m",
    unread: 0,
    accentColor: "#F4B740",
  },
  {
    name: "Sable Ward",
    subline: "IDENTITY VERIFIED · TRUST ESTABLISHED",
    time: "1h",
    unread: 1,
    accentColor: "#E85D75",
  },
  {
    name: "Daemon Process",
    subline: "relay latency: 12ms",
    time: "3h",
    unread: 0,
    accentColor: "#00D4FF",
  },
  {
    name: "Nova Reyes",
    subline: "the mesh is holding",
    time: "yesterday",
    unread: 0,
    accentColor: "#2FBF71",
  },
];

export default function ChatListPage() {
  return (
    <PhoneFrame>
      {/* Header */}
      <div
        style={{
          background: "#0F1318",
          borderBottom: "1px solid rgba(255,255,255,0.06)",
          padding: "20px 20px 18px",
          flexShrink: 0,
        }}
      >
        <div className="flex items-center justify-between">
          {/* Wordmark */}
          <span
            style={{
              color: "#E8F4F8",
              fontSize: "22px",
              fontWeight: 200,
              letterSpacing: "6px",
              textTransform: "uppercase",
            }}
          >
            PHANTOM
          </span>

          {/* Relay indicator */}
          <div className="flex items-center gap-1.5">
            <span
              className="animate-relay-pulse"
              style={{
                display: "block",
                width: 5,
                height: 5,
                borderRadius: "50%",
                background: "#2FBF71",
                boxShadow: "0 0 5px #2FBF7199",
              }}
            />
            <span
              style={{
                color: "#2FBF71",
                fontSize: "10px",
                letterSpacing: "1.5px",
                fontWeight: 500,
              }}
            >
              RELAY
            </span>
          </div>
        </div>

        {/* Section label */}
        <div
          style={{
            marginTop: "18px",
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <span
            style={{
              color: "#6B8A9A",
              fontSize: "10px",
              letterSpacing: "1.8px",
              textTransform: "uppercase",
              fontWeight: 400,
            }}
          >
            MESSAGES
          </span>
          <Link
            href="/connect"
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              width: 32,
              height: 32,
              borderRadius: "50%",
              background: "rgba(0,212,255,0.15)",
              border: "1px solid rgba(0,212,255,0.4)",
              color: "#00D4FF",
              boxShadow: "0 0 12px rgba(0,212,255,0.12)",
              transition: "all 0.2s",
            }}
            title="Add contact"
          >
            <svg
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
              <circle cx="9" cy="7" r="4" />
              <line x1="19" y1="8" x2="19" y2="14" />
              <line x1="16" y1="11" x2="22" y2="11" />
            </svg>
          </Link>
        </div>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>
        {conversations.map((conv) => (
          <ConversationItem
            key={conv.name}
            name={conv.name}
            subline={conv.subline}
            time={conv.time}
            unread={conv.unread}
            accentColor={conv.accentColor}
            href="/chat"
          />
        ))}

        {/* Spacer */}
        <div style={{ height: 24 }} />
      </div>

      <BottomNav active="chats" unreadChats={conversations.reduce((sum, c) => sum + (c.unread || 0), 0)} />
    </PhoneFrame>
  );
}
