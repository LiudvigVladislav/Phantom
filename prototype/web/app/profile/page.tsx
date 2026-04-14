import PhoneFrame from "@/components/layout/PhoneFrame";
import StatusBar from "@/components/ui/StatusBar";
import Avatar from "@/components/ui/Avatar";

const safetyNumbers = [
  ["04821", "73619", "28847"],
  ["91023", "55702", "16384"],
];

export default function ProfilePage() {
  return (
    <PhoneFrame>
      <StatusBar
        title="Aria Chen"
        showBack
        backHref="/chat"
        relayOnline={true}
      />

      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>
        {/* Profile hero */}
        <div
          className="flex flex-col items-center pt-10 pb-8 px-6"
          style={{
            background: "linear-gradient(180deg, #13161D 0%, #0B0D12 100%)",
            borderBottom: "1px solid rgba(255,255,255,0.06)",
          }}
        >
          <Avatar name="Aria Chen" size="xl" />

          <h2
            className="mt-4 font-bold"
            style={{ color: "#F4F7FB", fontSize: "22px" }}
          >
            Aria Chen
          </h2>
          <p
            className="mt-1 font-medium"
            style={{ color: "#6C5CE7", fontSize: "14px" }}
          >
            @aria_chen
          </p>

          {/* Trust badge */}
          <div
            className="flex items-center gap-1.5 mt-3 px-3 py-1.5 rounded-full"
            style={{
              background: "rgba(47,191,113,0.12)",
              border: "1px solid rgba(47,191,113,0.3)",
            }}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#2FBF71" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <span
              className="font-semibold"
              style={{ color: "#2FBF71", fontSize: "12px" }}
            >
              Verified
            </span>
          </div>

          {/* Device ID */}
          <div
            className="mt-4 px-3 py-2 rounded-lg flex items-center gap-2"
            style={{
              background: "#1A1D27",
              border: "1px solid rgba(255,255,255,0.06)",
            }}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#8A8FA3" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
              <line x1="12" y1="18" x2="12.01" y2="18" />
            </svg>
            <span
              className="font-mono"
              style={{ color: "#8A8FA3", fontSize: "11px", letterSpacing: "0.08em" }}
            >
              Device · 3F:A2:09:EC:71:44
            </span>
          </div>
        </div>

        {/* Safety Number */}
        <div className="px-4 pt-6 pb-4">
          <div className="mb-3 flex items-center justify-between">
            <span
              className="font-medium tracking-widest uppercase"
              style={{ color: "#8A8FA3", fontSize: "10px", letterSpacing: "0.14em" }}
            >
              Safety Number
            </span>
            <span style={{ color: "#8A8FA3", fontSize: "11px" }}>What is this?</span>
          </div>

          <div
            className="rounded-2xl p-4"
            style={{
              background: "#13161D",
              border: "1px solid rgba(255,255,255,0.06)",
            }}
          >
            <div className="grid gap-2" style={{ gridTemplateColumns: "1fr 1fr 1fr" }}>
              {safetyNumbers.flat().map((num, i) => (
                <div
                  key={i}
                  className="flex items-center justify-center py-2.5 rounded-lg"
                  style={{
                    background: "#1A1D27",
                    border: "1px solid rgba(255,255,255,0.04)",
                  }}
                >
                  <span
                    className="font-mono font-medium tracking-widest"
                    style={{ color: "#F4F7FB", fontSize: "13px", letterSpacing: "0.12em" }}
                  >
                    {num}
                  </span>
                </div>
              ))}
            </div>

            <p
              className="mt-3 text-center"
              style={{ color: "#8A8FA3", fontSize: "11px", lineHeight: "1.5" }}
            >
              Read these aloud with Aria to confirm your conversation is secure.
            </p>
          </div>
        </div>

        {/* Verify button */}
        <div className="px-4 pb-4">
          <button
            className="w-full py-3.5 rounded-2xl font-semibold transition-opacity active:opacity-70"
            style={{
              border: "1px solid rgba(108,92,231,0.4)",
              color: "#6C5CE7",
              fontSize: "14px",
              background: "transparent",
            }}
          >
            Verify with contact
          </button>
        </div>

        {/* Info rows */}
        <div className="px-4 pb-6">
          <div
            className="mb-3"
          >
            <span
              className="font-medium tracking-widest uppercase"
              style={{ color: "#8A8FA3", fontSize: "10px", letterSpacing: "0.14em" }}
            >
              Info
            </span>
          </div>
          <div
            className="rounded-2xl overflow-hidden"
            style={{ border: "1px solid rgba(255,255,255,0.06)" }}
          >
            {[
              { label: "Member since", value: "2025-11-04" },
              { label: "Messages", value: "1,247" },
              { label: "Relay node", value: "Berlin · de-1.phantom" },
            ].map((row, i, arr) => (
              <div
                key={row.label}
                className="flex items-center justify-between px-4 py-3.5"
                style={{
                  background: "#13161D",
                  borderBottom: i < arr.length - 1 ? "1px solid rgba(255,255,255,0.06)" : "none",
                }}
              >
                <span style={{ color: "#8A8FA3", fontSize: "13px" }}>{row.label}</span>
                <span style={{ color: "#F4F7FB", fontSize: "13px", fontWeight: 500 }}>{row.value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Danger zone */}
        <div className="px-4 pb-8">
          <button
            className="w-full py-3 rounded-2xl font-medium transition-opacity active:opacity-70"
            style={{
              border: "1px solid rgba(232,93,117,0.3)",
              color: "#E85D75",
              fontSize: "13px",
              background: "rgba(232,93,117,0.05)",
            }}
          >
            Block contact
          </button>
        </div>
      </div>
    </PhoneFrame>
  );
}
