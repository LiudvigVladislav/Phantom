"use client";

import React, { useState, useRef, useEffect } from "react";
import PhoneFrame from "@/components/layout/PhoneFrame";
import ChatBubble from "@/components/ui/ChatBubble";
import Avatar from "@/components/ui/Avatar";
import Link from "next/link";

type MessageStatus = "queued" | "sent" | "relayed" | "delivered" | "read" | undefined;

interface Message {
  id: number;
  text: string;
  sent: boolean;
  status: MessageStatus;
  time: string;
  ephemeral: boolean;
}

const initialMessages: Message[] = [
  {
    id: 1,
    text: "Hey, just confirmed the key exchange. Everything looks good on my end.",
    sent: false,
    status: undefined,
    time: "10:41",
    ephemeral: false,
  },
  {
    id: 2,
    text: "Same here. The fingerprint matches perfectly. We're verified.",
    sent: true,
    status: "read",
    time: "10:42",
    ephemeral: false,
  },
  {
    id: 3,
    text: "This channel is routing through the Berlin relay node. Latency is under 20ms.",
    sent: false,
    status: undefined,
    time: "10:43",
    ephemeral: false,
  },
  {
    id: 4,
    text: "Impressive. The mesh is holding up well.",
    sent: true,
    status: "delivered",
    time: "10:44",
    ephemeral: false,
  },
  {
    id: 5,
    text: "Sending you the location — this disappears in an hour.",
    sent: false,
    status: undefined,
    time: "10:45",
    ephemeral: true,
  },
  {
    id: 6,
    text: "Got it. I'll memorize and acknowledge.",
    sent: true,
    status: "sent",
    time: "10:46",
    ephemeral: false,
  },
];

export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>(initialMessages);
  const [inputText, setInputText] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  function handleSend() {
    const text = inputText.trim();
    if (!text) return;
    setMessages((prev) => [
      ...prev,
      {
        id: prev.length + 1,
        text,
        sent: true,
        status: "queued" as const,
        time: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        ephemeral: false,
      },
    ]);
    setInputText("");
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter") handleSend();
  }

  return (
    <PhoneFrame>
      {/* Custom chat header */}
      <div
        className="flex items-center px-3 shrink-0"
        style={{
          height: "60px",
          background: "#0F1318",
          borderBottom: "1px solid rgba(255,255,255,0.06)",
        }}
      >
        {/* Back */}
        <Link
          href="/"
          className="flex items-center justify-center w-9 h-9 rounded-full mr-1 transition-colors"
          style={{ color: "#6B8A9A" }}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </Link>

        {/* Avatar + info */}
        <Link href="/profile" className="flex items-center gap-2.5 flex-1 min-w-0">
          <Avatar name="Aria Chen" size="sm" />
          <div className="flex flex-col min-w-0">
            <span
              className="font-semibold truncate"
              style={{ color: "#E8F4F8", fontSize: "15px" }}
            >
              Aria Chen
            </span>
            <div className="flex items-center gap-1.5">
              <span
                className="block w-1.5 h-1.5 rounded-full"
                style={{ background: "#2FBF71", boxShadow: "0 0 4px rgba(47,191,113,0.8)" }}
              />
              <span style={{ color: "#6B8A9A", fontSize: "11px" }}>Online via relay</span>
            </div>
          </div>
        </Link>

        {/* Right actions */}
        <div className="flex items-center gap-1 ml-2">
          {/* Relay chip */}
          <div
            className="flex items-center gap-1 px-2 py-1 rounded-full"
            style={{
              background: "rgba(47,191,113,0.12)",
              border: "1px solid rgba(47,191,113,0.25)",
            }}
          >
            <span
              className="block w-1.5 h-1.5 rounded-full"
              style={{ background: "#2FBF71" }}
            />
            <span style={{ color: "#2FBF71", fontSize: "10px", fontWeight: 600 }}>Relay</span>
          </div>

          {/* More */}
          <button
            className="flex items-center justify-center w-9 h-9 rounded-full"
            style={{ color: "#6B8A9A" }}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
              <circle cx="12" cy="5" r="1.5" />
              <circle cx="12" cy="12" r="1.5" />
              <circle cx="12" cy="19" r="1.5" />
            </svg>
          </button>
        </div>
      </div>

      {/* Date separator */}
      <div
        className="flex items-center justify-center py-3 shrink-0"
        style={{ background: "#0B0D12" }}
      >
        <div
          className="px-3 py-1 rounded-full"
          style={{
            background: "rgba(255,255,255,0.05)",
            color: "#6B8A9A",
            fontSize: "11px",
          }}
        >
          Today
        </div>
      </div>

      {/* Messages */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-4 flex flex-col gap-2 pb-3"
        style={{ background: "#0B0D12" }}
      >
        {messages.map((msg) => (
          <ChatBubble
            key={msg.id}
            text={msg.text}
            sent={msg.sent}
            status={msg.status}
            ephemeral={msg.ephemeral}
            time={msg.time}
          />
        ))}
      </div>

      {/* Input bar */}
      <div
        className="flex items-center gap-2 px-3 py-2 shrink-0"
        style={{
          background: "#0F1318",
          borderTop: "1px solid rgba(255,255,255,0.06)",
          paddingBottom: "calc(0.5rem + env(safe-area-inset-bottom, 0px))",
        }}
      >
        {/* Attachment */}
        <button
          className="flex items-center justify-center w-9 h-9 rounded-full shrink-0"
          style={{ color: "#6B8A9A" }}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
          </svg>
        </button>

        {/* Text field */}
        <div
          className="flex-1 flex items-center px-3 rounded-2xl"
          style={{
            minHeight: "40px",
            background: "#141820",
            border: "1px solid rgba(255,255,255,0.08)",
          }}
        >
          <input
            type="text"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Message…"
            className="flex-1 bg-transparent outline-none"
            style={{
              color: "#E8F4F8",
              fontSize: "14px",
              caretColor: "#00D4FF",
            }}
          />
          {/* Ephemeral toggle */}
          <button style={{ color: "#6B8A9A", marginLeft: "6px" }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
          </button>
        </div>

        {/* Send */}
        <button
          onClick={handleSend}
          className="flex items-center justify-center w-9 h-9 rounded-full shrink-0 transition-all active:scale-95"
          style={{
            background: inputText.trim() ? "#00D4FF" : "rgba(0,212,255,0.2)",
            boxShadow: inputText.trim() ? "0 4px 16px rgba(0,212,255,0.45)" : "none",
          }}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <line x1="22" y1="2" x2="11" y2="13" />
            <polygon points="22 2 15 22 11 13 2 9 22 2" />
          </svg>
        </button>
      </div>
    </PhoneFrame>
  );
}
