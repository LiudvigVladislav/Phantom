import React from "react";

export type MessageStatus = "queued" | "sent" | "relayed" | "delivered" | "read";

interface ChatMessageProps {
  text: string;
  sent: boolean;
  status?: MessageStatus;
  ephemeral?: boolean;
  time?: string;
}

function DeliveryMark({ status }: { status: MessageStatus }) {
  const baseStyle: React.CSSProperties = { flexShrink: 0 };

  if (status === "queued") {
    // ○ queued
    return (
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none" style={baseStyle}>
        <circle cx="5" cy="5" r="4" stroke="#6B8A9A" strokeWidth="1.2" />
      </svg>
    );
  }
  if (status === "sent") {
    // — sent (short dash)
    return (
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none" style={baseStyle}>
        <line x1="2" y1="5" x2="8" y2="5" stroke="#6B8A9A" strokeWidth="1.4" strokeLinecap="round" />
      </svg>
    );
  }
  if (status === "relayed") {
    // ◎ relayed
    return (
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none" style={baseStyle}>
        <circle cx="5" cy="5" r="4" stroke="#6B8A9A" strokeWidth="1.2" />
        <circle cx="5" cy="5" r="2" stroke="#6B8A9A" strokeWidth="1" />
      </svg>
    );
  }
  if (status === "delivered") {
    // ● delivered
    return (
      <svg width="10" height="10" viewBox="0 0 10 10" fill="none" style={baseStyle}>
        <circle cx="5" cy="5" r="4" fill="#6B8A9A" />
      </svg>
    );
  }
  if (status === "read") {
    // ●● read
    return (
      <span style={{ display: "flex", gap: 2, ...baseStyle }}>
        <svg width="8" height="8" viewBox="0 0 8 8" fill="none">
          <circle cx="4" cy="4" r="3.2" fill="#80E8FF" />
        </svg>
        <svg width="8" height="8" viewBox="0 0 8 8" fill="none">
          <circle cx="4" cy="4" r="3.2" fill="#80E8FF" />
        </svg>
      </span>
    );
  }
  return null;
}

export default function ChatMessage({
  text,
  sent,
  status,
  ephemeral = false,
  time,
}: ChatMessageProps) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: sent ? "flex-end" : "flex-start",
      }}
    >
      {ephemeral ? (
        /* Ephemeral: thin outline, timer icon */
        <div
          style={{
            display: "inline-flex",
            alignItems: "flex-start",
            gap: 8,
            padding: "9px 14px",
            border: "1px solid rgba(108,92,231,0.3)",
            maxWidth: "78%",
          }}
        >
          {/* Timer glyph */}
          <svg
            width="13"
            height="13"
            viewBox="0 0 24 24"
            fill="none"
            stroke="#00D4FF"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            style={{ flexShrink: 0, marginTop: 2 }}
          >
            <circle cx="12" cy="12" r="9" />
            <polyline points="12 7 12 12 15 14" />
          </svg>
          <span
            style={{
              color: "#6B8A9A",
              fontSize: "15px",
              lineHeight: "1.6",
            }}
          >
            {text}
          </span>
        </div>
      ) : (
        <span
          style={{
            color: sent ? "#E8F4F8" : "#6B8A9A",
            fontSize: "15px",
            lineHeight: "1.6",
            textAlign: sent ? "right" : "left",
            maxWidth: "78%",
          }}
        >
          {text}
        </span>
      )}

      {/* Meta row: time + delivery */}
      {(time || (sent && status)) && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 5,
            marginTop: 3,
            justifyContent: sent ? "flex-end" : "flex-start",
          }}
        >
          {time && (
            <span
              style={{
                color: "#6B8A9A",
                fontSize: "10px",
                letterSpacing: "0.3px",
              }}
            >
              {time}
            </span>
          )}
          {sent && status && <DeliveryMark status={status} />}
        </div>
      )}
    </div>
  );
}
