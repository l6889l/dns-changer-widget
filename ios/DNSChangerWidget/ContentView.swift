import SwiftUI

/// Main screen — mirrors the Android app's activity_main.xml (same layout order,
/// labels, hints, presets and colors).
struct ContentView: View {
    @ObservedObject private var dns = DNSManager.shared
    @State private var dnsPrimary: String = ""
    @State private var dnsSecondary: String = ""
    @State private var showAddWidgetHelp = false
    @State private var showPermissionDenied = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Header
                Text("DNS Changer Widget")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(Color(hex: 0x1A237E))
                    .padding(.bottom, 4)

                Text(statusText)
                    .font(.system(size: 16))
                    .foregroundColor(statusColor)
                    .padding(.bottom, 8)

                // Add Widget (iOS has no API to pin a widget programmatically,
                // so this shows instructions — like the Android fallback dialog)
                Button {
                    showAddWidgetHelp = true
                } label: {
                    Text("Add Widget")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(hex: 0x1A237E))
                .controlSize(.large)
                .padding(.bottom, 8)

                if dns.status == .starting {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 8)
                }

                // DNS settings
                Text("Primary DNS")
                    .font(.system(size: 16, weight: .bold))
                    .padding(.top, 16)
                    .padding(.bottom, 4)

                TextField("1.1.1.1 or dns.example.com", text: $dnsPrimary)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.bottom, 4)

                Text("Secondary DNS (optional)")
                    .font(.system(size: 16, weight: .bold))
                    .padding(.top, 12)
                    .padding(.bottom, 4)

                TextField("8.8.4.4 or dns2.example.com", text: $dnsSecondary)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                // Presets
                Text("Presets:")
                    .font(.system(size: 16, weight: .bold))
                    .padding(.top, 16)
                    .padding(.bottom, 8)

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible())], spacing: 8) {
                    ForEach(DNSChangerShared.presets, id: \.name) { preset in
                        Button {
                            dnsPrimary = preset.primary
                            dnsSecondary = preset.secondary
                        } label: {
                            Text(preset.name)
                                .font(.system(size: 12))
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.bottom, 8)

                // Grant permission / Disconnect. Android keeps the "Grant VPN
                // permission" button and stops via the notification actions;
                // iOS has no such notification, so the same button becomes
                // "Disconnect" while the proxy is running.
                Button {
                    primaryButtonTapped()
                } label: {
                    Text(dns.status == .on ? "Disconnect" : "Grant VPN permission")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(dns.status == .on ? Color(hex: 0x9E9E9E) : Color(hex: 0x1A237E))
                .controlSize(.large)
                .padding(.top, 24)
            }
            .padding(20)
        }
        .background(Color(hex: 0xF5F5F5).ignoresSafeArea())
        .onAppear {
            dnsPrimary = dns.primaryDNS
            dnsSecondary = dns.secondaryDNS
            dns.refresh()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                dns.refresh()
            }
        }
        .alert("Add the widget", isPresented: $showAddWidgetHelp) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Press and hold the home screen until the apps jiggle, tap Edit, tap Add Widget, then find DNS Changer Widget and add it.")
        }
        .alert("VPN permission denied", isPresented: $showPermissionDenied) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("The VPN permission is required to change the DNS servers.")
        }
    }

    private var statusText: String {
        switch dns.status {
        case .on: return "Status: ON"
        case .off: return "Status: OFF — DNS is managed by the system"
        case .starting: return "Status: Starting..."
        }
    }

    private var statusColor: Color {
        switch dns.status {
        case .on: return Color(hex: 0x4CAF50)
        case .off: return Color(hex: 0x000000)
        case .starting: return Color(hex: 0x9E9E9E)
        }
    }

    private func primaryButtonTapped() {
        if dns.status == .on {
            dns.disconnect()
        } else {
            dns.saveDNS(
                primary: dnsPrimary.trimmingCharacters(in: .whitespacesAndNewlines),
                secondary: dnsSecondary.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            dns.connect { granted in
                if !granted {
                    showPermissionDenied = true
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
