import Foundation

/// Shared constants used by the app, the widget extension and the DNS proxy extension.
/// Keys mirror the Android app's SharedPreferences keys (`dns_prefs`).
enum DNSChangerShared {
    static let appGroup = "group.com.example.dnschanger"
    static let proxyBundleID = "com.example.dnschanger.dnsproxy"

    /// Keys used in the app-group UserDefaults (shared with the widget) and in the
    /// DNS proxy's `providerConfiguration` dictionary.
    static let primaryKey = "dns_primary"
    static let secondaryKey = "dns_secondary"
    static let enabledKey = "dns_enabled"

    /// Fallback server used when the primary DNS cannot be resolved (mirrors the
    /// Android app, which falls back to 1.1.1.1).
    static let defaultPrimary = "1.1.1.1"

    struct DNSPreset: Identifiable {
        let name: String
        let primary: String
        let secondary: String
        var id: String { name }
    }

    /// Same presets as the Android app.
    static let presets: [DNSPreset] = [
        DNSPreset(name: "Google DNS", primary: "8.8.8.8", secondary: "8.8.4.4"),
        DNSPreset(name: "Cloudflare", primary: "1.1.1.1", secondary: "1.0.0.1"),
        DNSPreset(name: "OpenDNS", primary: "208.67.222.222", secondary: "208.67.220.220"),
        DNSPreset(name: "AdGuard", primary: "dns.adguard-dns.com", secondary: "")
    ]
}
