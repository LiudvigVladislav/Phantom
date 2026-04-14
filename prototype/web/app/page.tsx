import PhoneFrame from "@/components/layout/PhoneFrame";
import StatusBar from "@/components/ui/StatusBar";
import BottomNav from "@/components/ui/BottomNav";
import ConversationItem from "@/components/ui/ConversationItem";

const conversations = [
  {
    name: "Aria Chen",
    lastMessage: "The keys are confirmed on my end.",
    time: "now",
    unread: 3,
    verified: true,
  },
  {
    name: "Marcus Yael",
    lastMessage: "Send me the backup phrase when you're ready.",
    time: "2m",
    unread: 0,
    verified: true,
  },
  {
    name: "Encrypted Group",
    lastMessage: "You: See you on the other side.",
    time: "14m",
    unread: 0,
    verified: false,
  },
  {
    name: "Sable Ward",
    lastMessage: "Identity verified. Trust established.",
    time: "1h",
    unread: 1,
    verified: false,
  },
  {
    name: "Daemon Process",
    lastMessage: "Node relay latency: 12ms",
    time: "3h",
    unread: 0,
    verified: false,
  },
];

export default function ChatListPage() {
  return (
    <PhoneFrame>
      <StatusBar title="PHANTOM" relayOnline={true} />

      {/* Search bar */}
      <div
        className="px-4 py-3"
        style={{ background: "#13161D", borderBottom: "1px solid rgba(255,255,255,0.06)" }}
      >
        <div
          className="flex items-center gap-2 px-3 rounded-xl"
          style={{
            height: "38px",
            background: "#1A1D27",
            border: "1px solid rgba(255,255,255,0.06)",
          }}
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#8A8FA3" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span style={{ color: "#8A8FA3", fontSize: "14px" }}>Search conversations…</span>
        </div>
      </div>

      {/* Section label */}
      <div
        className="px-4 pt-4 pb-2"
        style={{ background: "#0B0D12" }}
      >
        <span
          className="font-medium tracking-widest uppercase"
          style={{ color: "#8A8FA3", fontSize: "10px", letterSpacing: "0.14em" }}
        >
          Messages
        </span>
      </div>

      {/* Conversation list */}
      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>
        {conversations.map((conv) => (
          <ConversationItem
            key={conv.name}
            name={conv.name}
            lastMessage={conv.lastMessage}
            time={conv.time}
            unread={conv.unread}
            verified={conv.verified}
            href="/chat"
          />
        ))}

        {/* New conversation CTA */}
        <div className="flex justify-center pt-8 pb-4">
          <button
            className="flex items-center gap-2 px-5 py-2.5 rounded-xl font-medium transition-opacity active:opacity-70"
            style={{
              background: "rgba(108,92,231,0.15)",
              border: "1px solid rgba(108,92,231,0.35)",
              color: "#6C5CE7",
              fontSize: "13px",
            }}
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            New conversation
          </button>
        </div>
      </div>

      <BottomNav active="chats" />
    </PhoneFrame>
  );
}
