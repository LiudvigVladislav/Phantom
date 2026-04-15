"use client";

import React, { useState } from "react";
import PhoneFrame from "@/components/layout/PhoneFrame";
import StatusBar from "@/components/ui/StatusBar";
import BottomNav from "@/components/ui/BottomNav";
import Link from "next/link";

function Toggle({ checked, onChange }: { checked: boolean; onChange: () => void }) {
  return (
    <button
      onClick={onChange}
      className="relative inline-flex items-center rounded-full transition-all"
      style={{
        width: 44,
        height: 24,
        background: checked ? "#00D4FF" : "#141820",
        border: `2px solid ${checked ? "#00D4FF" : "rgba(255,255,255,0.1)"}`,
        boxShadow: checked ? "0 0 12px rgba(0,212,255,0.4)" : "none",
      }}
    >
      <span
        className="inline-block rounded-full transition-all"
        style={{
          width: 16,
          height: 16,
          background: "#ffffff",
          transform: checked ? "translateX(22px)" : "translateX(2px)",
        }}
      />
    </button>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-4 pt-6 pb-2">
      <span
        className="font-medium tracking-widest uppercase"
        style={{ color: "#6B8A9A", fontSize: "10px", letterSpacing: "0.14em" }}
      >
        {children}
      </span>
    </div>
  );
}

function SettingsRow({
  icon,
  label,
  value,
  toggle,
  onToggle,
  danger = false,
  chevron = false,
  last = false,
}: {
  icon: React.ReactNode;
  label: string;
  value?: string;
  toggle?: boolean;
  onToggle?: () => void;
  danger?: boolean;
  chevron?: boolean;
  last?: boolean;
}) {
  return (
    <div
      className="flex items-center gap-3 px-4"
      style={{
        height: "52px",
        borderBottom: last ? "none" : "1px solid rgba(255,255,255,0.06)",
        background: "#0F1318",
      }}
    >
      <div
        className="flex items-center justify-center rounded-lg shrink-0"
        style={{
          width: 32,
          height: 32,
          background: danger ? "rgba(232,93,117,0.1)" : "rgba(0,212,255,0.12)",
          color: danger ? "#E85D75" : "#00D4FF",
        }}
      >
        {icon}
      </div>
      <span
        className="flex-1 font-medium"
        style={{ color: danger ? "#E85D75" : "#E8F4F8", fontSize: "14px" }}
      >
        {label}
      </span>
      {value && (
        <span style={{ color: "#6B8A9A", fontSize: "13px" }}>{value}</span>
      )}
      {toggle !== undefined && onToggle && (
        <Toggle checked={toggle} onChange={onToggle} />
      )}
      {chevron && (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#6B8A9A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="9 18 15 12 9 6" />
        </svg>
      )}
    </div>
  );
}

export default function SettingsPage() {
  const [messageRequests, setMessageRequests] = useState(true);
  const [disappearing, setDisappearing] = useState(false);
  const [relayAutoSelect, setRelayAutoSelect] = useState(true);

  return (
    <PhoneFrame>
      <StatusBar title="Settings" relayOnline={true} />

      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>

        {/* Identity card */}
        <div className="px-4 pt-5 pb-4">
          <div
            className="flex items-center gap-3 p-4 rounded-2xl"
            style={{
              background: "#0F1318",
              border: "1px solid rgba(255,255,255,0.06)",
            }}
          >
            <div
              className="flex items-center justify-center rounded-full font-bold shrink-0"
              style={{
                width: 52,
                height: 52,
                background: "linear-gradient(135deg, #00D4FF 0%, #80E8FF 100%)",
                color: "#fff",
                fontSize: "20px",
              }}
            >
              Y
            </div>
            <div className="flex-1 min-w-0">
              <p className="font-semibold" style={{ color: "#E8F4F8", fontSize: "16px" }}>
                @phantom_user
              </p>
              <p style={{ color: "#6B8A9A", fontSize: "12px", marginTop: "2px" }}>
                Device · 3F:A2:09:EC:71:44
              </p>
            </div>
            <div
              className="flex items-center gap-1 px-2 py-1 rounded-full"
              style={{
                background: "rgba(47,191,113,0.12)",
                border: "1px solid rgba(47,191,113,0.25)",
              }}
            >
              <span className="block w-1.5 h-1.5 rounded-full" style={{ background: "#2FBF71" }} />
              <span style={{ color: "#2FBF71", fontSize: "10px", fontWeight: 600 }}>Active</span>
            </div>
          </div>
        </div>

        {/* Transport */}
        <SectionLabel>Transport</SectionLabel>
        <div
          className="mx-4 rounded-2xl overflow-hidden"
          style={{ border: "1px solid rgba(255,255,255,0.06)" }}
        >
          {/* Relay address */}
          <div
            className="px-4 py-3"
            style={{ background: "#0F1318", borderBottom: "1px solid rgba(255,255,255,0.06)" }}
          >
            <div className="flex items-center justify-between mb-2">
              <span style={{ color: "#6B8A9A", fontSize: "12px" }}>Relay address</span>
              <span
                className="font-mono"
                style={{ color: "#00D4FF", fontSize: "11px" }}
              >
                relay.phantom.net
              </span>
            </div>
            {/* Health bar */}
            <div
              className="flex items-center gap-2"
            >
              <div
                className="flex-1 rounded-full overflow-hidden"
                style={{ height: 4, background: "rgba(255,255,255,0.06)" }}
              >
                <div
                  className="h-full rounded-full"
                  style={{
                    width: "98%",
                    background: "linear-gradient(90deg, #2FBF71, #1aa35f)",
                    boxShadow: "0 0 8px rgba(47,191,113,0.5)",
                  }}
                />
              </div>
              <span style={{ color: "#2FBF71", fontSize: "11px", fontWeight: 600, minWidth: 28 }}>98%</span>
            </div>
            <p style={{ color: "#6B8A9A", fontSize: "11px", marginTop: "4px" }}>
              Latency 11ms · Berlin node
            </p>
          </div>

          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M5 12.55a11 11 0 0 1 14.08 0" />
                <path d="M1.42 9a16 16 0 0 1 21.16 0" />
                <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
                <line x1="12" y1="20" x2="12.01" y2="20" />
              </svg>
            }
            label="Auto-select relay"
            toggle={relayAutoSelect}
            onToggle={() => setRelayAutoSelect((v) => !v)}
            last
          />
        </div>

        {/* Nearby */}
        <SectionLabel>Connectivity</SectionLabel>
        <div
          className="mx-4 rounded-2xl overflow-hidden"
          style={{ border: "1px solid rgba(255,255,255,0.06)" }}
        >
          <Link href="/nearby" className="block">
            <SettingsRow
              icon={
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="2" />
                  <path d="M16.24 7.76a6 6 0 0 1 0 8.49m-8.48-.01a6 6 0 0 1 0-8.49m11.31-2.82a10 10 0 0 1 0 14.14m-14.14 0a10 10 0 0 1 0-14.14" />
                </svg>
              }
              label="Nearby / Offline mode"
              chevron
              last
            />
          </Link>
        </div>

        {/* Privacy */}
        <SectionLabel>Privacy</SectionLabel>
        <div
          className="mx-4 rounded-2xl overflow-hidden"
          style={{ border: "1px solid rgba(255,255,255,0.06)" }}
        >
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
            }
            label="Message requests"
            toggle={messageRequests}
            onToggle={() => setMessageRequests((v) => !v)}
          />
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <polyline points="12 6 12 12 16 14" />
              </svg>
            }
            label="Disappearing messages"
            toggle={disappearing}
            onToggle={() => setDisappearing((v) => !v)}
            last
          />
        </div>

        {/* Identity */}
        <SectionLabel>Identity</SectionLabel>
        <div
          className="mx-4 rounded-2xl overflow-hidden"
          style={{ border: "1px solid rgba(255,255,255,0.06)" }}
        >
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            }
            label="Username"
            value="@phantom_user"
            chevron
          />
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            }
            label="Export identity"
            chevron
            last
          />
        </div>

        {/* About */}
        <SectionLabel>About</SectionLabel>
        <div
          className="mx-4 mb-8 rounded-2xl overflow-hidden"
          style={{ border: "1px solid rgba(255,255,255,0.06)" }}
        >
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              </svg>
            }
            label="Version"
            value="0.1.0-alpha0"
          />
          <SettingsRow
            icon={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            }
            label="Privacy policy"
            chevron
            last
          />
        </div>
      </div>

      <BottomNav active="settings" />
    </PhoneFrame>
  );
}
