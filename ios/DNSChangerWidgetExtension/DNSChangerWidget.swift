import WidgetKit
import SwiftUI
import AppIntents

/// Home-screen / lock-screen widget mirroring the Android app's widget_layout.xml:
/// title "Custom DNS", status "ON" (green) / "OFF (Tap to start!)" (gray).
struct DNSChangerEntry: TimelineEntry {
    let date: Date
    let enabled: Bool
}

struct DNSChangerProvider: TimelineProvider {
    func placeholder(in context: Context) -> DNSChangerEntry {
        DNSChangerEntry(date: Date(), enabled: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (DNSChangerEntry) -> Void) {
        completion(DNSChangerEntry(date: Date(), enabled: currentState()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DNSChangerEntry>) -> Void) {
        let entry = DNSChangerEntry(date: Date(), enabled: currentState())
        // The timeline is also reloaded after every toggle (via WidgetCenter).
        let nextUpdate = Date().addingTimeInterval(30 * 60)
        completion(Timeline(entries: [entry], policy: .after(nextUpdate)))
    }

    private func currentState() -> Bool {
        UserDefaults(suiteName: DNSChangerShared.appGroup)?
            .bool(forKey: DNSChangerShared.enabledKey) ?? false
    }
}

struct DNSChangerEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DNSChangerEntry

    var body: some View {
        switch family {
        case .accessoryInline:
            Text(entry.enabled ? "DNS ON" : "DNS OFF")
                .widgetAccentable()

        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 2) {
                Text("Custom DNS")
                    .font(.headline)
                    .widgetAccentable()
                Text(entry.enabled ? "ON" : "OFF (Tap to start!)")
                    .font(.subheadline)
                    .foregroundStyle(entry.enabled ? Color(hex: 0x4CAF50) : .secondary)
            }

        default:
            VStack(spacing: 4) {
                Text("Custom DNS")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(Color(hex: 0x1A237E))
                    .lineLimit(1)
                // iOS 17+ interactive button: toggles the DNS proxy via the app
                // process (see ToggleDNSIntent in Shared/).
                Button(intent: ToggleDNSIntent()) {
                    Text(entry.enabled ? "ON" : "OFF (Tap to start!)")
                        .font(.system(size: 10))
                        .foregroundColor(entry.enabled ? Color(hex: 0x4CAF50) : Color(hex: 0x9E9E9E))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .buttonStyle(.plain)
            }
            .padding(4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .containerBackground(for: .widget) {
                Color(hex: entry.enabled ? 0xE8F5E9 : 0xF5F5F5)
            }
        }
    }
}

struct DNSChangerWidget: Widget {
    let kind = "DNSChangerWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: DNSChangerProvider()) { entry in
            DNSChangerEntryView(entry: entry)
        }
        .configurationDisplayName("DNS Changer Widget")
        .description("Shows the custom DNS status. Tap to turn it on or off.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryInline, .accessoryRectangular])
    }
}

@main
struct DNSChangerWidgetBundle: WidgetBundle {
    var body: some Widget {
        DNSChangerWidget()
    }
}
