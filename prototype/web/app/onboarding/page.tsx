"use client";

import React, { useState } from "react";
import PhoneFrame from "@/components/layout/PhoneFrame";
import Link from "next/link";

function Step1({ onNext }: { onNext: () => void }) {
  return (
    <div className="flex flex-col flex-1 px-8 pt-20 pb-12">
      {/* Wordmark */}
      <div className="flex-1 flex flex-col items-center justify-center">
        <div className="mb-12 flex flex-col items-center gap-6">
          {/* Shield icon */}
          <div
            className="flex items-center justify-center rounded-3xl"
            style={{
              width: 80,
              height: 80,
              background: "rgba(108,92,231,0.15)",
              border: "1px solid rgba(108,92,231,0.3)",
            }}
          >
            <svg width="38" height="38" viewBox="0 0 24 24" fill="none" stroke="#6C5CE7" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </div>

          <div className="text-center">
            <h1
              className="font-bold tracking-widest"
              style={{
                fontSize: "40px",
                letterSpacing: "0.22em",
                color: "#F4F7FB",
              }}
            >
              PHANTOM
            </h1>
            <p
              className="mt-3 font-light tracking-wide"
              style={{ color: "#8A8FA3", fontSize: "16px", letterSpacing: "0.04em" }}
            >
              Your identity. Your keys.
            </p>
          </div>
        </div>

        {/* Feature list */}
        <div className="flex flex-col gap-4 w-full max-w-xs">
          {[
            { icon: "🔑", text: "End-to-end encrypted by default" },
            { icon: "🌐", text: "No phone number required" },
            { icon: "⚡", text: "Relay mesh — works without servers" },
          ].map((item) => (
            <div
              key={item.text}
              className="flex items-center gap-3 px-4 py-3 rounded-xl"
              style={{
                background: "#13161D",
                border: "1px solid rgba(255,255,255,0.06)",
              }}
            >
              <span style={{ fontSize: "18px" }}>{item.icon}</span>
              <span style={{ color: "#8A8FA3", fontSize: "13px" }}>{item.text}</span>
            </div>
          ))}
        </div>
      </div>

      <button
        onClick={onNext}
        className="w-full py-4 rounded-2xl font-semibold tracking-wide transition-opacity active:opacity-80"
        style={{
          background: "#6C5CE7",
          color: "#ffffff",
          fontSize: "15px",
          letterSpacing: "0.04em",
          boxShadow: "0 8px 32px rgba(108,92,231,0.4)",
        }}
      >
        Get started
      </button>

      <p
        className="text-center mt-4"
        style={{ color: "#8A8FA3", fontSize: "12px" }}
      >
        No account, no email, no number.
      </p>
    </div>
  );
}

function Step2({ onNext }: { onNext: () => void }) {
  const [username, setUsername] = useState("");

  return (
    <div className="flex flex-col flex-1 px-8 pt-16 pb-12">
      <div className="mb-10">
        <h2
          className="font-bold mb-2"
          style={{ color: "#F4F7FB", fontSize: "28px" }}
        >
          Choose your handle
        </h2>
        <p style={{ color: "#8A8FA3", fontSize: "14px", lineHeight: "1.6" }}>
          This is how others will find you. It is the only identity you need.
        </p>
      </div>

      <div className="flex flex-col gap-3 mb-6">
        <label
          className="font-medium tracking-wide uppercase"
          style={{ color: "#8A8FA3", fontSize: "10px", letterSpacing: "0.12em" }}
        >
          Username
        </label>
        <div
          className="flex items-center px-4 rounded-xl"
          style={{
            height: "52px",
            background: "#13161D",
            border: `1px solid ${username ? "rgba(108,92,231,0.5)" : "rgba(255,255,255,0.08)"}`,
          }}
        >
          <span
            className="font-medium mr-1"
            style={{ color: "#6C5CE7", fontSize: "16px" }}
          >
            @
          </span>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, ""))}
            placeholder="your_handle"
            className="flex-1 bg-transparent outline-none"
            style={{
              color: "#F4F7FB",
              fontSize: "16px",
              caretColor: "#6C5CE7",
            }}
          />
          {username.length >= 3 && (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#2FBF71" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          )}
        </div>
        <p style={{ color: "#8A8FA3", fontSize: "12px" }}>
          Letters, numbers, and underscores. 3-24 characters.
        </p>
      </div>

      <div className="flex-1" />

      <button
        onClick={onNext}
        disabled={username.length < 3}
        className="w-full py-4 rounded-2xl font-semibold tracking-wide transition-all active:opacity-80"
        style={{
          background: username.length >= 3 ? "#6C5CE7" : "#1A1D27",
          color: username.length >= 3 ? "#ffffff" : "#8A8FA3",
          fontSize: "15px",
          letterSpacing: "0.04em",
          boxShadow: username.length >= 3 ? "0 8px 32px rgba(108,92,231,0.4)" : "none",
          cursor: username.length >= 3 ? "pointer" : "default",
        }}
      >
        Continue
      </button>
    </div>
  );
}

function Step3() {
  return (
    <div className="flex flex-col flex-1 px-8 pt-12 pb-12">
      <div className="mb-8">
        <h2
          className="font-bold mb-2"
          style={{ color: "#F4F7FB", fontSize: "28px" }}
        >
          Your identity
        </h2>
        <p style={{ color: "#8A8FA3", fontSize: "14px", lineHeight: "1.6" }}>
          Share this QR or link to let others add you securely.
        </p>
      </div>

      {/* QR Placeholder */}
      <div className="flex justify-center mb-6">
        <div
          className="relative flex items-center justify-center"
          style={{
            width: 220,
            height: 220,
            background: "#13161D",
            borderRadius: "16px",
            border: "1px solid rgba(255,255,255,0.06)",
          }}
        >
          {/* Corner marks */}
          {[
            { top: 12, left: 12, rotate: "0deg" },
            { top: 12, right: 12, rotate: "90deg" },
            { bottom: 12, right: 12, rotate: "180deg" },
            { bottom: 12, left: 12, rotate: "270deg" },
          ].map((pos, i) => (
            <svg
              key={i}
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="#6C5CE7"
              strokeWidth="2.5"
              strokeLinecap="round"
              style={{
                position: "absolute",
                ...pos,
                transform: `rotate(${pos.rotate})`,
              }}
            >
              <path d="M2 8 L2 2 L8 2" />
            </svg>
          ))}

          {/* Center content */}
          <div className="flex flex-col items-center gap-2">
            <div
              className="flex items-center justify-center rounded-xl"
              style={{
                width: 48,
                height: 48,
                background: "rgba(108,92,231,0.2)",
                border: "1px solid rgba(108,92,231,0.4)",
              }}
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#6C5CE7" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              </svg>
            </div>
            <span
              className="font-bold tracking-widest"
              style={{ color: "#F4F7FB", fontSize: "11px", letterSpacing: "0.18em" }}
            >
              PHANTOM
            </span>

            {/* Mock QR dots pattern — deterministic */}
            <div
              className="grid gap-0.5 mt-1"
              style={{ gridTemplateColumns: "repeat(8, 6px)" }}
            >
              {Array.from({ length: 48 }).map((_, i) => {
                const filled = [0,1,2,5,6,7,8,9,14,15,17,19,20,21,22,23,24,25,26,28,30,31,33,35,36,37,38,39,40,42,44,45,46,47];
                return (
                <div
                  key={i}
                  style={{
                    width: 6,
                    height: 6,
                    borderRadius: "1px",
                    background: filled.includes(i) ? "#6C5CE7" : "transparent",
                    opacity: 0.7,
                  }}
                />
                );
              })}
            </div>
          </div>
        </div>
      </div>

      <div
        className="flex items-center justify-center px-4 py-2 rounded-lg mb-6"
        style={{
          background: "#13161D",
          border: "1px solid rgba(255,255,255,0.06)",
        }}
      >
        <span
          className="font-medium"
          style={{ color: "#6C5CE7", fontSize: "15px" }}
        >
          @phantom_user
        </span>
      </div>

      {/* Action buttons */}
      <div className="flex flex-col gap-3">
        <button
          className="w-full py-3.5 rounded-2xl font-semibold transition-opacity active:opacity-80"
          style={{
            background: "#6C5CE7",
            color: "#ffffff",
            fontSize: "14px",
            boxShadow: "0 8px 32px rgba(108,92,231,0.4)",
          }}
        >
          Copy invite link
        </button>
        <Link
          href="/"
          className="w-full py-3.5 rounded-2xl font-semibold text-center transition-opacity active:opacity-80"
          style={{
            background: "transparent",
            border: "1px solid rgba(108,92,231,0.4)",
            color: "#6C5CE7",
            fontSize: "14px",
          }}
        >
          Open PHANTOM
        </Link>
      </div>
    </div>
  );
}

export default function OnboardingPage() {
  const [step, setStep] = useState(0);

  return (
    <PhoneFrame>
      {/* Step indicator */}
      <div
        className="flex items-center justify-center gap-2 pt-6 pb-2 shrink-0"
        style={{ background: "#0B0D12" }}
      >
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            style={{
              width: i === step ? 24 : 6,
              height: 6,
              borderRadius: 3,
              background: i === step ? "#6C5CE7" : "rgba(108,92,231,0.25)",
              transition: "all 0.3s ease",
            }}
          />
        ))}
      </div>

      <div
        className="flex flex-col flex-1 overflow-hidden"
        style={{ background: "#0B0D12" }}
      >
        {step === 0 && <Step1 onNext={() => setStep(1)} />}
        {step === 1 && <Step2 onNext={() => setStep(2)} />}
        {step === 2 && <Step3 />}
      </div>
    </PhoneFrame>
  );
}
