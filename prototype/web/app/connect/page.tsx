import PhoneFrame from "@/components/layout/PhoneFrame";
import StatusBar from "@/components/ui/StatusBar";
import BottomNav from "@/components/ui/BottomNav";

export default function ConnectPage() {
  return (
    <PhoneFrame>
      <StatusBar
        title="Add contact"
        showBack
        backHref="/"
        relayOnline={true}
      />

      <div className="flex-1 overflow-y-auto" style={{ background: "#0B0D12" }}>

        {/* Tabs */}
        <div
          className="flex px-4 pt-4 gap-2"
          style={{ borderBottom: "1px solid rgba(255,255,255,0.06)" }}
        >
          {["QR Code", "Username", "Link"].map((tab, i) => (
            <div
              key={tab}
              className="pb-3 px-1 relative font-medium"
              style={{
                color: i === 0 ? "#F4F7FB" : "#8A8FA3",
                fontSize: "13px",
              }}
            >
              {tab}
              {i === 0 && (
                <span
                  className="absolute bottom-0 left-0 right-0 h-0.5 rounded-full"
                  style={{ background: "#6C5CE7" }}
                />
              )}
            </div>
          ))}
        </div>

        {/* QR Block */}
        <div className="flex flex-col items-center px-6 pt-8 pb-6">
          {/* QR Placeholder */}
          <div
            className="relative flex items-center justify-center mb-4"
            style={{
              width: 240,
              height: 240,
              background: "#13161D",
              borderRadius: "20px",
              border: "1px solid rgba(255,255,255,0.06)",
              boxShadow: "0 0 60px rgba(108,92,231,0.08)",
            }}
          >
            {/* Corner bracket marks */}
            {[
              { top: 14, left: 14, rotate: "0deg" },
              { top: 14, right: 14, rotate: "90deg" },
              { bottom: 14, right: 14, rotate: "180deg" },
              { bottom: 14, left: 14, rotate: "270deg" },
            ].map((pos, i) => (
              <svg
                key={i}
                width="28"
                height="28"
                viewBox="0 0 28 28"
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
                <path d="M3 12 L3 3 L12 3" />
              </svg>
            ))}

            {/* Accent glow */}
            <div
              style={{
                position: "absolute",
                inset: 0,
                borderRadius: "20px",
                background: "radial-gradient(circle at center, rgba(108,92,231,0.06) 0%, transparent 70%)",
              }}
            />

            {/* Center content */}
            <div className="flex flex-col items-center gap-2 z-10">
              <div
                className="flex items-center justify-center rounded-2xl"
                style={{
                  width: 52,
                  height: 52,
                  background: "rgba(108,92,231,0.15)",
                  border: "1px solid rgba(108,92,231,0.4)",
                }}
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#6C5CE7" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                </svg>
              </div>

              <span
                className="font-bold tracking-widest"
                style={{ color: "#F4F7FB", fontSize: "12px", letterSpacing: "0.2em" }}
              >
                PHANTOM
              </span>

              {/* Mock QR pixel pattern */}
              <div
                className="grid mt-2"
                style={{
                  gridTemplateColumns: "repeat(9, 7px)",
                  gap: "2px",
                }}
              >
                {Array.from({ length: 63 }).map((_, i) => {
                  const corners = [0, 1, 2, 9, 10, 11, 18, 19, 20, 6, 7, 8, 15, 16, 17, 24, 25, 26];
                  const isFilled = corners.includes(i) || (i > 26 && i % 3 !== 1);
                  return (
                    <div
                      key={i}
                      style={{
                        width: 7,
                        height: 7,
                        borderRadius: "1.5px",
                        background: isFilled ? "#6C5CE7" : "rgba(108,92,231,0.12)",
                        opacity: isFilled ? 0.85 : 0.3,
                      }}
                    />
                  );
                })}
              </div>
            </div>
          </div>

          {/* Username pill */}
          <div
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl mb-6"
            style={{
              background: "#13161D",
              border: "1px solid rgba(255,255,255,0.06)",
            }}
          >
            <span style={{ color: "#8A8FA3", fontSize: "13px" }}>Your identity:</span>
            <span className="font-semibold" style={{ color: "#6C5CE7", fontSize: "14px" }}>
              @phantom_user
            </span>
          </div>

          {/* Action buttons */}
          <div className="flex flex-col gap-3 w-full">
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

            <button
              className="w-full py-3.5 rounded-2xl font-semibold transition-opacity active:opacity-80 flex items-center justify-center gap-2"
              style={{
                border: "1px solid rgba(108,92,231,0.35)",
                color: "#6C5CE7",
                fontSize: "14px",
                background: "transparent",
              }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="23 7 16 12 23 17 23 7" />
                <rect x="1" y="5" width="15" height="14" rx="2" ry="2" />
              </svg>
              Scan QR
            </button>

            <button
              className="w-full py-3.5 rounded-2xl font-semibold transition-opacity active:opacity-80 flex items-center justify-center gap-2"
              style={{
                border: "1px solid rgba(255,255,255,0.08)",
                color: "#8A8FA3",
                fontSize: "14px",
                background: "transparent",
              }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="18" cy="5" r="3" />
                <circle cx="6" cy="12" r="3" />
                <circle cx="18" cy="19" r="3" />
                <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
                <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
              </svg>
              Share link
            </button>
          </div>

          {/* Privacy note */}
          <div
            className="flex items-center gap-2 mt-5 px-4 py-3 rounded-xl w-full"
            style={{
              background: "rgba(47,191,113,0.06)",
              border: "1px solid rgba(47,191,113,0.15)",
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#2FBF71" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
            <span style={{ color: "#8A8FA3", fontSize: "12px", lineHeight: "1.5" }}>
              Your phone number is never shared
            </span>
          </div>
        </div>
      </div>

      <BottomNav active="contacts" />
    </PhoneFrame>
  );
}
