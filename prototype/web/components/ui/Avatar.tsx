import React from "react";

interface AvatarProps {
  name: string;
  size?: "sm" | "md" | "lg" | "xl";
}

const sizeMap = {
  sm: { px: 32, fontSize: "12px" },
  md: { px: 44, fontSize: "16px" },
  lg: { px: 64, fontSize: "22px" },
  xl: { px: 88, fontSize: "30px" },
};

function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].charAt(0).toUpperCase();
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
}

// Deterministic gradient selection based on name
function getGradient(name: string): string {
  const gradients = [
    "linear-gradient(135deg, #00D4FF 0%, #80E8FF 100%)",
    "linear-gradient(135deg, #007AA3 0%, #00D4FF 100%)",
    "linear-gradient(135deg, #009EC2 0%, #66DBFF 100%)",
    "linear-gradient(135deg, #005F82 0%, #00D4FF 100%)",
    "linear-gradient(135deg, #00D4FF 0%, #0099CC 100%)",
  ];
  const idx = name.split("").reduce((acc, ch) => acc + ch.charCodeAt(0), 0) % gradients.length;
  return gradients[idx];
}

export default function Avatar({ name, size = "md" }: AvatarProps) {
  const { px, fontSize } = sizeMap[size];
  const initials = getInitials(name);
  const gradient = getGradient(name);

  return (
    <div
      className="flex items-center justify-center rounded-full shrink-0 select-none"
      style={{
        width: px,
        height: px,
        background: gradient,
        fontSize,
        fontWeight: 600,
        color: "#ffffff",
        letterSpacing: "0.02em",
      }}
    >
      {initials}
    </div>
  );
}
