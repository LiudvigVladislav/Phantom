import SwiftUI

// PHANTOM design palette — single source of truth for iOS.
// Keep in sync with PhantomTheme.kt in apps/android.
extension Color {
    /// Primary interactive accent — used for buttons, highlights, icons.
    static let cyanAccent   = Color(hex: "00D4FF")
    /// Deepest background layer.
    static let bgDeep       = Color(hex: "0B0D12")
    /// Card / sheet surface.
    static let surface      = Color(hex: "0F1318")
    /// Elevated surface (second-level cards, input fields).
    static let surface2     = Color(hex: "141820")
    /// Primary readable text.
    static let textPrimary  = Color(hex: "E8F4F8")
    /// Secondary / dimmed text and placeholders.
    static let textDim      = Color(hex: "6B8A9A")
    /// Destructive action and error state.
    static let danger       = Color(hex: "E85D75")
    /// Positive / confirmation state.
    static let success      = Color(hex: "2FBF71")
}

private extension Color {
    /// Initialise from a 6-character hex string without a leading '#'.
    init(hex: String) {
        let scanner = Scanner(string: hex)
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        let r = Double((rgb >> 16) & 0xFF) / 255
        let g = Double((rgb >> 8)  & 0xFF) / 255
        let b = Double( rgb        & 0xFF) / 255
        self.init(red: r, green: g, blue: b)
    }
}
