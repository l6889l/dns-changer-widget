import SwiftUI

extension Color {
    /// Initializes a color from a 0xRRGGBB value, using the same palette as the
    /// Android app's colors.xml.
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255.0,
                  green: Double((hex >> 8) & 0xFF) / 255.0,
                  blue: Double(hex & 0xFF) / 255.0,
                  opacity: 1.0)
    }
}
