import AppIntents
import Foundation
import NetworkExtension
import WidgetKit

/// Widget button action: turns the custom DNS proxy on or off without opening the app.
///
/// The intent type is compiled into BOTH the app target and the widget extension
/// target (this file lives in `Shared/`). The `ForegroundContinuableIntent`
/// conformance (app-only) makes the system run the intent in the app process in the
/// background, where the app has the Network Extensions (`dns-proxy`) entitlement.
/// A widget extension process alone cannot manage the DNS proxy configuration.
struct ToggleDNSIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Custom DNS"
    static var description = IntentDescription("Turns the custom DNS proxy on or off")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        await DNSProxyToggle.toggle()
        return .result()
    }
}

@available(iOSApplicationExtension, unavailable)
extension ToggleDNSIntent: ForegroundContinuableIntent {}

/// Self-contained toggle implementation shared by the app and the widget targets.
/// The copy compiled into the widget extension is never executed (the intent runs
/// in the app process), but it must compile there too.
enum DNSProxyToggle {
    static func toggle() async {
        let manager = NEDNSProxyManager.shared()

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            manager.loadFromPreferences { _ in
                continuation.resume()
            }
        }

        let currentlyEnabled = manager.isEnabled

        // If the user never granted permission from the app yet, create the
        // configuration with the last saved servers so the toggle has something to save.
        if manager.providerProtocol == nil {
            let proto = NEDNSProxyProviderProtocol()
            proto.providerBundleIdentifier = DNSChangerShared.proxyBundleID
            proto.serverAddress = DNSChangerShared.defaultPrimary
            proto.providerConfiguration = [
                DNSChangerShared.primaryKey: DNSChangerShared.defaultPrimary,
                DNSChangerShared.secondaryKey: ""
            ]
            manager.providerProtocol = proto
            manager.localizedDescription = "DNS Changer"
        }

        manager.isEnabled = !currentlyEnabled

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            manager.saveToPreferences { _ in
                continuation.resume()
            }
        }

        UserDefaults(suiteName: DNSChangerShared.appGroup)?
            .set(manager.isEnabled, forKey: DNSChangerShared.enabledKey)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
