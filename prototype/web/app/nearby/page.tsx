"use client";

import React, { useState } from "react";
import PhoneFrame from "@/components/layout/PhoneFrame";
import StatusBar from "@/components/ui/StatusBar";

function SignalBars({ strength }: { strength: 1 | 2 | 3 | 4 }) {
  return (
    <div className="flex items-end gap-0.5" style={{ height: 16 }}>
      {[1, 2, 3, 4].map((level) => (
        <div
          key={level}
          style={{
            width: 4,
            height: 4 + level * 3,
            borderRadius: 1,
            background: level <= strength ? "#00D4FF" : "rgba(255,255,255,0.12)",
          }}
        />
      ))}
    </div>
  );
}

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

const nearbyDevices = [
  { id: 1, name: "Unknown device", label: "8A:F3:…:11", signal: 3 as const },
  { id: 2, name: "Unknown device", label: "C2:01:…:77", signal: 1 as const },
];

export default function NearbyPage() {
  const [broadcast, setBroadcast] = useState(false);

  return (
    <PhoneFrame>
      <StatusBar
        title="Offline Mode"
        showBack
        backHref="/settings"
        relayOnline={false}
      />

      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>

        {/* Hero */}
        <div className="px-6 pt-8 pb-6 text-center">
          <div
            className="mx-auto mb-5 flex items-center justify-center rounded-3xl"
            style={{
              width: 72,
              height: 72,
              background: "rgba(0,212,255,0.1)",
              border: "1px solid rgba(0,212,255,0.25)",
            }}
          >
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#00D4FF" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="2" />
              <path d="M16.24 7.76a6 6 0 0 1 0 8.49m-8.48-.01a6 6 0 0 1 0-8.49m11.31-2.82a10 10 0 0 1 0 14.14m-14.14 0a10 10 0 0 1 0-14.14" />
            </svg>
          </div>
          <h2
            className="font-bold mb-2"
            style={{ color: "#E8F4F8", fontSize: "22px" }}
          >
            Connect directly
          </h2>
          <p
            style={{ color: "#6B8A9A", fontSize: "14px", lineHeight: "1.6", maxWidth: 280, margin: "0 auto" }}
          >
            Reach other PHANTOM users nearby without internet access.
          </p>
        </div>

        {/* Transport chips */}
        <div className="px-4 pb-5">
          <div className="flex gap-2">
            {[
              { label: "Wi-Fi Direct", icon: "📶" },
              { label: "Bluetooth", icon: "🔵" },
            ].map((chip) => (
              <div
                key={chip.label}
                className="flex items-center gap-2 px-3 py-2 rounded-xl"
                style={{
                  background: "#0F1318",
                  border: "1px solid rgba(255,255,255,0.06)",
                }}
              >
                <span style={{ fontSize: "13px" }}>{chip.icon}</span>
                <span style={{ color: "#E8F4F8", fontSize: "12px", fontWeight: 500 }}>
                  {chip.label}
                </span>
                <span
                  className="px-1.5 py-0.5 rounded font-medium"
                  style={{
                    background: "rgba(255,255,255,0.06)",
                    color: "#6B8A9A",
                    fontSize: "9px",
                    letterSpacing: "0.06em",
                  }}
                >
                  Not in Alpha-0
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Broadcast toggle */}
        <div className="px-4 pb-4">
          <div
            className="px-4 py-3 rounded-2xl flex items-center justify-between"
            style={{
              background: "#0F1318",
              border: "1px solid rgba(255,255,255,0.06)",
            }}
          >
            <div className="flex items-center gap-3">
              <div
                className="flex items-center justify-center rounded-lg"
                style={{
                  width: 36,
                  height: 36,
                  background: broadcast ? "rgba(0,212,255,0.15)" : "rgba(255,255,255,0.04)",
                  border: `1px solid ${broadcast ? "rgba(0,212,255,0.3)" : "rgba(255,255,255,0.06)"}`,
                }}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={broadcast ? "#00D4FF" : "#6B8A9A"} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07A19.5 19.5 0 0 1 4.7 12a19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 3.61 1h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 8.91a16 16 0 0 0 6 6l.91-.91a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                </svg>
              </div>
              <div>
                <p className="font-medium" style={{ color: "#E8F4F8", fontSize: "14px" }}>
                  Broadcast presence
                </p>
                <p style={{ color: "#6B8A9A", fontSize: "11px" }}>
                  {broadcast ? "Visible to nearby PHANTOM users" : "Invisible — passive scan only"}
                </p>
              </div>
            </div>
            <Toggle checked={broadcast} onChange={() => setBroadcast((v) => !v)} />
          </div>
        </div>

        {/* Device list */}
        <div className="px-4 pb-4">
          <div className="mb-3 flex items-center justify-between">
            <span
              className="font-medium tracking-widest uppercase"
              style={{ color: "#6B8A9A", fontSize: "10px", letterSpacing: "0.14em" }}
            >
              Detected devices
            </span>
            <span style={{ color: "#6B8A9A", fontSize: "11px" }}>Scanning…</span>
          </div>

          <div
            className="rounded-2xl overflow-hidden"
            style={{ border: "1px solid rgba(255,255,255,0.06)" }}
          >
            {nearbyDevices.map((device, i) => (
              <div
                key={device.id}
                className="flex items-center gap-3 px-4"
                style={{
                  height: "60px",
                  background: "#0F1318",
                  borderBottom: i < nearbyDevices.length - 1 ? "1px solid rgba(255,255,255,0.06)" : "none",
                }}
              >
                {/* Signal */}
                <SignalBars strength={device.signal} />

                {/* Info */}
                <div className="flex-1 min-w-0">
                  <p className="font-medium" style={{ color: "#E8F4F8", fontSize: "13px" }}>
                    {device.name}
                  </p>
                  <p
                    className="font-mono"
                    style={{ color: "#6B8A9A", fontSize: "11px" }}
                  >
                    {device.label}
                  </p>
                </div>

                {/* Lock icon */}
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6B8A9A" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>

                <button
                  className="px-3 py-1.5 rounded-lg font-medium"
                  style={{
                    background: "rgba(0,212,255,0.15)",
                    border: "1px solid rgba(0,212,255,0.3)",
                    color: "#00D4FF",
                    fontSize: "11px",
                  }}
                >
                  Connect
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Footnote */}
        <div className="px-6 pb-10">
          <p
            className="text-center"
            style={{ color: "#6B8A9A", fontSize: "11px", lineHeight: "1.7" }}
          >
            Direct connections are end-to-end encrypted with the same keys as your relay messages. No data transits external servers.
          </p>
        </div>
      </div>
    </PhoneFrame>
  );
}
