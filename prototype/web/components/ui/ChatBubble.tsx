import React from "react";

type MessageStatus = "queued" | "sent" | "relayed" | "delivered" | "read";

interface ChatBubbleProps {
  text: string;
  sent: boolean;
  status?: MessageStatus;
  ephemeral?: boolean;
  time?: string;
}

function StatusIcon({ status }: { status: MessageStatus }) {
  if (status === "queued") {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ opacity: 0.5 }}>
        <circle cx="12" cy="12" r="10" />
        <polyline points="12 6 12 12 16 14" />
      </svg>
    );
  }
  if (status === "sent") {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ opacity: 0.65 }}>
        <polyline points="20 6 9 17 4 12" />
      </svg>
    );
  }
  if (status === "relayed") {
    return (
      <svg width="16" height="14" viewBox="0 0 28 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ opacity: 0.65 }}>
        <polyline points="4 12 9 17 20 6" />
        <polyline points="12 12 17 17 28 6" />
      </svg>
    );
  }
  if (status === "delivered") {
    return (
      <svg width="16" height="14" viewBox="0 0 28 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ opacity: 0.8 }}>
        <polyline points="4 12 9 17 20 6" />
        <polyline points="12 12 17 17 28 6" />
      </svg>
    );
  }
  if (status === "read") {
    return (
      <svg width="16" height="14" viewBox="0 0 28 24" fill="none" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="4 12 9 17 20 6" stroke="#80E8FF" />
        <polyline points="12 12 17 17 28 6" stroke="#80E8FF" />
      </svg>
    );
  }
  return null;
}

function EphemeralIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}

export default function ChatBubble({ text, sent, status, ephemeral = false, time }: ChatBubbleProps) {
  return (
    <div
      className="flex w-full"
      style={{ justifyContent: sent ? "flex-end" : "flex-start" }}
    >
      <div
        className="flex flex-col"
        style={{
          maxWidth: "72%",
          minWidth: "80px",
          padding: "10px 14px 8px",
          borderRadius: sent ? "18px 18px 4px 18px" : "18px 18px 18px 4px",
          background: sent ? "#00D4FF" : "#141820",
          boxShadow: sent
            ? "0 4px 16px rgba(0,212,255,0.25)"
            : "0 2px 8px rgba(0,0,0,0.3)",
        }}
      >
        {ephemeral && (
          <div
            className="flex items-center gap-1 mb-1.5"
            style={{ color: sent ? "rgba(255,255,255,0.7)" : "#6B8A9A", fontSize: "11px" }}
          >
            <EphemeralIcon />
            <span className="font-medium">Disappears in 1h</span>
          </div>
        )}

        <span
          className="leading-relaxed"
          style={{
            color: sent ? "#ffffff" : "#E8F4F8",
            fontSize: "14px",
            lineHeight: "1.5",
          }}
        >
          {text}
        </span>

        <div
          className="flex items-center justify-end gap-1 mt-1"
          style={{ color: sent ? "rgba(255,255,255,0.55)" : "#6B8A9A" }}
        >
          {time && (
            <span style={{ fontSize: "11px" }}>{time}</span>
          )}
          {sent && status && <StatusIcon status={status} />}
        </div>
      </div>
    </div>
  );
}
