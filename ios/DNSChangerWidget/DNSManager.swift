import Foundation
import NetworkExtension
import WidgetKit

enum DNSStatus: Equatable {
    case off
    case starting
    case on
}

/// Manages the DNS proxy configuration through NEDNSProxyManager.
/// Mirrors the Android app's behavior (DnsVpnService + SharedPreferences):
/// DNS servers are saved before the proxy starts, and the enabled state is kept
/// in the app-group UserDefaults so the widget can display it.
final class DNSManager: ObservableObject {
    static let shared = DNSManager()

    @Published private(set) var status: DNSStatus = .off

    private let manager = NEDNSProxyManager.shared()
    private let defaults: UserDefaults?

    private init() {
        defaults = UserDefaults(suiteName: DNSChangerShared.appGroup)

        // Keep the UI in sync when the configuration changes (e.g. toggled
        // from the widget or from Settings).
        NotificationCenter.default.addObserver(
            forName: .NEDNSProxyConfigurationDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refresh()
        }
    }

    var primaryDNS: String { defaults?.string(forKey: DNSChangerShared.primaryKey) ?? "" }
    var secondaryDNS: String { defaults?.string(forKey: DNSChangerShared.secondaryKey) ?? "" }

    func saveDNS(primary: String, secondary: String) {
        defaults?.set(primary, forKey: DNSChangerShared.primaryKey)
        defaults?.set(secondary, forKey: DNSChangerShared.secondaryKey)
    }

    /// Reloads the enabled state from the system (like the Android app's onResume).
    func refresh(completion: (() -> Void)? = nil) {
        manager.loadFromPreferences { [weak self] _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                let enabled = self.manager.isEnabled
                self.defaults?.set(enabled, forKey: DNSChangerShared.enabledKey)
                self.status = enabled ? .on : .off
                completion?()
            }
        }
    }

    /// Saves the DNS servers and starts the proxy — the iOS equivalent of the
    /// Android "Grant VPN permission" button. The first run shows the system
    /// permission prompt; afterwards the proxy starts immediately.
    /// - Parameter completion: called with `true` when the proxy is enabled,
    ///   `false` when the user denied permission or saving failed.
    func connect(completion: @escaping (Bool) -> Void) {
        status = .starting
        let primary = primaryDNS
        let secondary = secondaryDNS

        manager.loadFromPreferences { [weak self] _ in
            guard let self = self else { return }
            let wasEnabled = self.manager.isEnabled

            // Remember what is currently stored so we can detect DNS changes.
            let oldConfig = self.manager.providerProtocol?.providerConfiguration ?? [:]
            let oldPrimary = (oldConfig[DNSChangerShared.primaryKey] as? String) ?? ""
            let oldSecondary = (oldConfig[DNSChangerShared.secondaryKey] as? String) ?? ""

            let proto: NEDNSProxyProviderProtocol
            if let existing = self.manager.providerProtocol as? NEDNSProxyProviderProtocol {
                proto = existing
            } else {
                proto = NEDNSProxyProviderProtocol()
            }
            proto.providerBundleIdentifier = DNSChangerShared.proxyBundleID
            proto.serverAddress = primary.isEmpty ? DNSChangerShared.defaultPrimary : primary
            proto.providerConfiguration = [
                DNSChangerShared.primaryKey: primary,
                DNSChangerShared.secondaryKey: secondary
            ]
            self.manager.providerProtocol = proto
            self.manager.localizedDescription = "DNS Changer"

            let enable: () -> Void = { [weak self] in
                guard let self = self else { return }
                self.manager.isEnabled = true
                self.manager.saveToPreferences { [weak self] error in
                    guard let self = self else { return }
                    DispatchQueue.main.async {
                        if error != nil {
                            self.status = .off
                            completion(false)
                            return
                        }
                        // Reload to confirm the user approved the system prompt
                        // (on the first run they may have denied it).
                        self.manager.loadFromPreferences { _ in
                            DispatchQueue.main.async {
                                let enabled = self.manager.isEnabled
                                self.defaults?.set(enabled, forKey: DNSChangerShared.enabledKey)
                                self.status = enabled ? .on : .off
                                WidgetCenter.shared.reloadAllTimelines()
                                completion(enabled)
                            }
                        }
                    }
                }
            }

            let dnsChanged = oldPrimary != primary || oldSecondary != secondary
            if wasEnabled && dnsChanged {
                // Restart the proxy so the new servers take effect immediately.
                self.manager.isEnabled = false
                self.manager.saveToPreferences { _ in
                    enable()
                }
            } else {
                enable()
            }
        }
    }

    /// Stops the proxy (iOS equivalent of the Android notification "Stop" action).
    func disconnect(completion: (() -> Void)? = nil) {
        manager.loadFromPreferences { [weak self] _ in
            guard let self = self else { return }
            self.manager.isEnabled = false
            self.manager.saveToPreferences { _ in
                DispatchQueue.main.async {
                    self.defaults?.set(false, forKey: DNSChangerShared.enabledKey)
                    self.status = .off
                    WidgetCenter.shared.reloadAllTimelines()
                    completion?()
                }
            }
        }
    }
}
