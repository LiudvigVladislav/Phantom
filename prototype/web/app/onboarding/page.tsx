"use client";

import React, { useState } from "react";
import PhoneFrame from "@/components/layout/PhoneFrame";
import Link from "next/link";

/* ── Step 1 ─────────────────────────────────────────────── */
function Step1({ onNext }: { onNext: () => void }) {
  return (
    <div
      className="flex flex-col flex-1 animate-fade-in-up"
      style={{ padding: "0 32px" }}
    >
      {/* Top label */}
      <div
        style={{
          marginTop: 72,
          textAlign: "center",
        }}
      >
        <span
          style={{
            color: "#6B8A9A",
            fontSize: "11px",
            letterSpacing: "3px",
            textTransform: "uppercase",
          }}
        >
          PHANTOM MESSENGER
        </span>
      </div>

      {/* Headline */}
      <div style={{ marginTop: 32, textAlign: "center" }}>
        <p
          style={{
            color: "#E8F4F8",
            fontSize: "32px",
            fontWeight: 200,
            lineHeight: "1.25",
            letterSpacing: "-0.3px",
            margin: 0,
          }}
        >
          Your presence,
        </p>
        <p
          style={{
            color: "#E8F4F8",
            fontSize: "32px",
            fontWeight: 200,
            lineHeight: "1.25",
            letterSpacing: "-0.3px",
            margin: 0,
          }}
        >
          known to no one.
        </p>
      </div>

      {/* Air */}
      <div style={{ height: 40 }} />

      {/* Descriptor */}
      <p
        style={{
          color: "#6B8A9A",
          fontSize: "13px",
          lineHeight: "1.7",
          textAlign: "center",
          margin: 0,
        }}
      >
        End-to-end encrypted.
        <br />
        No phone required. Uncensorable.
      </p>

      {/* Spacer */}
      <div style={{ flex: 1 }} />

      {/* BEGIN button — thin horizontal line style */}
      <button
        onClick={onNext}
        className="phantom-btn-outline"
        style={{
          display: "block",
          width: "100%",
          position: "relative",
          padding: "18px 0",
          background: "transparent",
          border: "none",
          borderTop: "1px solid rgba(255,255,255,0.12)",
          borderBottom: "1px solid rgba(255,255,255,0.12)",
          cursor: "pointer",
          marginBottom: 48,
        }}
      >
        <span
          style={{
            color: "#E8F4F8",
            fontSize: "11px",
            letterSpacing: "4px",
            textTransform: "uppercase",
            fontWeight: 400,
          }}
        >
          BEGIN
        </span>
      </button>
    </div>
  );
}

/* ── Step 2 ─────────────────────────────────────────────── */
function Step2({ onNext }: { onNext: () => void }) {
  const [username, setUsername] = useState("");
  const valid = username.length >= 3;

  return (
    <div
      className="flex flex-col flex-1 animate-fade-in-up"
      style={{ padding: "0 32px" }}
    >
      <div style={{ marginTop: 72 }}>
        <h2
          style={{
            color: "#E8F4F8",
            fontSize: "24px",
            fontWeight: 300,
            letterSpacing: "0.5px",
            margin: 0,
          }}
        >
          Choose your identity
        </h2>
      </div>

      {/* Big underline input */}
      <div style={{ marginTop: 48 }}>
        <div
          style={{
            display: "flex",
            alignItems: "baseline",
            gap: 6,
            borderBottom: `1px solid ${valid ? "#00D4FF" : "rgba(255,255,255,0.2)"}`,
            paddingBottom: 12,
            transition: "border-color 200ms",
          }}
        >
          <span
            style={{
              color: "#00D4FF",
              fontSize: "22px",
              fontWeight: 300,
              lineHeight: 1,
            }}
          >
            @
          </span>
          <input
            type="text"
            value={username}
            onChange={(e) =>
              setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, ""))
            }
            placeholder="username"
            style={{
              flex: 1,
              background: "transparent",
              border: "none",
              outline: "none",
              color: "#E8F4F8",
              fontSize: "22px",
              fontWeight: 300,
              caretColor: "#00D4FF",
              letterSpacing: "0.5px",
              fontFamily: "inherit",
            }}
          />
        </div>

        <p
          style={{
            color: "#6B8A9A",
            fontSize: "11px",
            letterSpacing: "0.3px",
            marginTop: 12,
            lineHeight: "1.6",
          }}
        >
          Your username is stored in the distributed network.
          <br />
          No server owns it.
        </p>
      </div>

      <div style={{ flex: 1 }} />

      <button
        onClick={onNext}
        disabled={!valid}
        className="phantom-btn-outline"
        style={{
          display: "block",
          width: "100%",
          position: "relative",
          padding: "18px 0",
          background: "transparent",
          border: "none",
          borderTop: `1px solid ${valid ? "rgba(0,212,255,0.6)" : "rgba(255,255,255,0.08)"}`,
          borderBottom: `1px solid ${valid ? "rgba(0,212,255,0.6)" : "rgba(255,255,255,0.08)"}`,
          cursor: valid ? "pointer" : "default",
          marginBottom: 48,
          transition: "border-color 200ms",
          opacity: valid ? 1 : 0.4,
        }}
      >
        <span
          style={{
            color: valid ? "#00D4FF" : "#6B8A9A",
            fontSize: "11px",
            letterSpacing: "4px",
            textTransform: "uppercase",
            fontWeight: 400,
            transition: "color 200ms",
          }}
        >
          CONTINUE
        </span>
      </button>
    </div>
  );
}

/* ── QR Decoration SVG ──────────────────────────────────── */
function PhantomQR() {
  // Decorative QR-like grid using a deterministic pattern
  const size = 240;
  const cellCount = 13;
  const cellSize = Math.floor(size / cellCount);
  const gap = 1;

  // Seed a pseudo-random pattern (deterministic)
  const seed = [
    1,1,1,1,1,1,1,0,1,1,1,1,1,
    1,0,0,0,0,0,1,0,1,0,0,0,1,
    1,0,1,1,1,0,1,0,1,0,1,0,1,
    1,0,1,1,1,0,1,0,1,0,1,0,1,
    1,0,1,1,1,0,1,0,1,0,1,0,1,
    1,0,0,0,0,0,1,0,1,0,0,0,1,
    1,1,1,1,1,1,1,0,1,1,1,1,1,
    0,0,1,0,0,1,0,1,0,0,1,0,0,
    1,1,0,1,0,1,1,0,1,1,0,1,1,
    1,0,0,0,0,0,1,0,1,0,0,0,1,
    1,0,1,1,1,0,1,0,1,0,1,0,1,
    1,0,0,0,0,0,1,0,1,0,0,0,1,
    1,1,1,1,1,1,1,0,1,1,1,1,1,
  ];

  const cells = seed.map((filled, i) => {
    const row = Math.floor(i / cellCount);
    const col = i % cellCount;
    // Reserve center area for symbol (rows 5-7, cols 5-7)
    const isCenterZone = row >= 5 && row <= 7 && col >= 5 && col <= 7;
    if (isCenterZone) return null;
    return {
      x: col * (cellSize + gap),
      y: row * (cellSize + gap),
      filled,
    };
  });

  return (
    <div
      style={{
        position: "relative",
        width: size,
        height: size,
        flexShrink: 0,
      }}
    >
      {/* Corner brackets — animated */}
      {[
        { top: 0, left: 0, rotate: "0deg" },
        { top: 0, right: 0, rotate: "90deg" },
        { bottom: 0, right: 0, rotate: "180deg" },
        { bottom: 0, left: 0, rotate: "270deg" },
      ].map((pos, i) => (
        <svg
          key={i}
          className="animate-corner-pulse"
          width="28"
          height="28"
          viewBox="0 0 28 28"
          fill="none"
          stroke="#00D4FF"
          strokeWidth="2.5"
          strokeLinecap="round"
          style={{
            position: "absolute",
            ...pos,
            transform: `rotate(${pos.rotate})`,
            animationDelay: `${i * 0.3}s`,
          }}
        >
          <path d="M3 14 L3 3 L14 3" />
        </svg>
      ))}

      {/* QR pixel grid */}
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        style={{ position: "absolute", inset: 0 }}
      >
        {cells.map((cell, i) => {
          if (!cell) return null;
          return (
            <rect
              key={i}
              x={cell.x}
              y={cell.y}
              width={cellSize - 1}
              height={cellSize - 1}
              rx={1}
              fill={
                cell.filled
                  ? "rgba(0,212,255,0.82)"
                  : "rgba(255,255,255,0.04)"
              }
            />
          );
        })}
      </svg>

      {/* Center symbol ⬡ */}
      <div
        style={{
          position: "absolute",
          top: "50%",
          left: "50%",
          transform: "translate(-50%,-50%)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          width: 40,
          height: 40,
          background: "#0B0D12",
          border: "1px solid rgba(0,212,255,0.4)",
        }}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#00D4FF"
          strokeWidth="1.5"
        >
          <polygon points="12 2 22 7 22 17 12 22 2 17 2 7" />
        </svg>
      </div>
    </div>
  );
}

/* ── Step 3 ─────────────────────────────────────────────── */
function Step3() {
  return (
    <div
      className="flex flex-col flex-1 items-center animate-fade-in-up"
      style={{ padding: "0 32px" }}
    >
      <div style={{ marginTop: 48, textAlign: "center" }}>
        <h2
          style={{
            color: "#E8F4F8",
            fontSize: "28px",
            fontWeight: 200,
            letterSpacing: "0.5px",
            margin: 0,
          }}
        >
          Your key
        </h2>
      </div>

      {/* QR */}
      <div style={{ marginTop: 32 }}>
        <PhantomQR />
      </div>

      {/* Username */}
      <span
        style={{
          color: "#00D4FF",
          fontSize: "15px",
          fontWeight: 400,
          letterSpacing: "1px",
          marginTop: 20,
        }}
      >
        @phantom_user
      </span>

      <div style={{ flex: 1 }} />

      {/* Two outline buttons in a row */}
      <div
        style={{
          display: "flex",
          gap: 12,
          width: "100%",
          marginBottom: 48,
        }}
      >
        <button
          className="phantom-btn-outline"
          style={{
            flex: 1,
            padding: "14px 0",
            background: "transparent",
            border: "1px solid rgba(0,212,255,0.35)",
            color: "#00D4FF",
            fontSize: "11px",
            letterSpacing: "2px",
            textTransform: "uppercase",
            cursor: "pointer",
            fontFamily: "inherit",
          }}
        >
          Copy link
        </button>
        <Link
          href="/"
          style={{
            flex: 1,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: "14px 0",
            border: "1px solid rgba(255,255,255,0.1)",
            color: "#6B8A9A",
            fontSize: "11px",
            letterSpacing: "2px",
            textTransform: "uppercase",
            textDecoration: "none",
          }}
        >
          Open
        </Link>
      </div>
    </div>
  );
}

/* ── Page ────────────────────────────────────────────────── */
export default function OnboardingPage() {
  const [step, setStep] = useState(0);

  return (
    <PhoneFrame>
      <div
        className="flex flex-col flex-1 overflow-hidden"
        style={{ background: "#0B0D12" }}
      >
        {/* Step 1 */}
        {step === 0 && <Step1 onNext={() => setStep(1)} />}
        {step === 1 && <Step2 onNext={() => setStep(2)} />}
        {step === 2 && <Step3 />}
      </div>
    </PhoneFrame>
  );
}
