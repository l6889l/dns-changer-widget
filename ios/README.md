# DNS Changer Widget — iOS

Native iOS counterpart of the Android app in this repository. Same functionality,
same interface and roughly the same UX, implemented with Apple's native APIs.

## What's inside

| Android                                | iOS equivalent (this folder)                                   |
|----------------------------------------|----------------------------------------------------------------|
| `VpnService` + `PacketForwarder` (DNS) | `NEDNSProxyProvider` extension (`DNSProxyExtension`)           |
| `DnsWidgetProvider` (home widget)      | WidgetKit widget (`DNSChangerWidgetExtension`)                 |
| `MainActivity` (settings screen)       | SwiftUI `ContentView`                                          |
| SharedPreferences `dns_prefs`          | App Group UserDefaults `group.com.example.dnschanger`          |
| `VpnService.prepare()` permission      | `NEDNSProxyManager` system permission prompt                   |
| Widget tap → toggle                    | Widget button → `ToggleDNSIntent` (runs in the app process)    |

How the DNS switching works on iOS: the app creates a DNS proxy configuration
(`NEDNSProxyManager`) pointing at the DNS servers you choose. The system asks for
permission once ("DNS Changer would like to add VPN configurations"). When enabled,
the `DNSProxyProvider` extension intercepts every DNS query on the device and
forwards it to the selected server (hostnames are resolved to IPs before the proxy
starts, mirroring the Android app). Responses are written back as if they came from
the original server, so no app notices the redirect.

The widget shows **Custom DNS / ON** (green) or **OFF (Tap to start!)** (gray),
exactly like the Android widget. Tapping it toggles the proxy: the intent is
compiled into both the app and the widget, and `ForegroundContinuableIntent`
makes the system run it in the app process in the background (the widget extension
process alone cannot manage the DNS proxy configuration).

## Project structure

```
ios/
├── project.yml                      # XcodeGen spec — the source of truth
├── ExportOptions.plist              # used by the optional signed CI build
├── Shared/
│   ├── DNSChangerShared.swift       # constants shared by all three targets
│   ├── ColorHex.swift               # 0xRRGGBB color helper (app + widget)
│   └── ToggleDNSIntent.swift        # widget button intent + toggle logic
├── DNSChangerWidget/                # the app (SwiftUI)
│   ├── DNSChangerApp.swift
│   ├── ContentView.swift            # mirrors activity_main.xml
│   ├── DNSManager.swift             # NEDNSProxyManager wrapper
│   └── Assets.xcassets/             # app icon
├── DNSChangerWidgetExtension/       # WidgetKit widget
│   └── DNSChangerWidget.swift
└── DNSProxyExtension/               # NEDNSProxyProvider extension
    └── DNSProxyProvider.swift
```

The `.xcodeproj` is **not** committed — it is generated from `project.yml` by
XcodeGen (same in CI and locally), so the project is fully reproducible.

## Building locally

You need a Mac with Xcode 15 or newer and [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
brew install xcodegen
cd ios
xcodegen generate
open DNSChangerWidget.xcodeproj
```

Then in Xcode: select your team under **Signing & Capabilities** and run on a
device. See "Signing" below.

## Signing & entitlements

The app and the DNS proxy extension require the **Network Extensions (DNS Proxy)**
capability; the app and widget also use an **App Group** (`group.com.example.dnschanger`)
to share the enabled state.

- The DNS Proxy capability requires a **paid Apple Developer Program account** —
  free personal teams cannot provision it. Development builds with a paid account
  work normally; for App Store distribution Apple reviews the capability
  (DNS-proxy apps are routinely approved, e.g. DNSCloak, AdGuard).
- If Xcode cannot create the provisioning profile automatically, open
  *Signing & Capabilities* and register the App Group and Network Extensions for
  each target (the three bundle IDs are `com.example.dnschanger`,
  `com.example.dnschanger.widget`, `com.example.dnschanger.dnsproxy`).

## CI (GitHub Actions)

`.github/workflows/ios-build.yml` — *Build iOS* — builds the project on a
**macOS runner** (no Mac needed locally), mirroring the Android *Build APK*
workflow:

- manual trigger (`workflow_dispatch`), same as the Android workflow;
- installs XcodeGen, generates the project, runs `xcodebuild` for a generic iOS
  device in Release configuration with code signing disabled;
- uploads the `.app` bundle as an artifact (`dns-changer-widget-ios-app`);
- **optional signed path**: if the `IOS_CERTIFICATE_BASE64` secret is set, it
  imports the certificate and provisioning profiles into the runner keychain,
  archives and exports a signed `.ipa` (`dns-changer-widget-ios-ipa`). This is the
  iOS equivalent of the Android release keystore secrets.

Unsigned builds are the default so the workflow works out of the box, exactly like
the Android debug APK step.

## Notes / differences from Android

- There is no API on iOS to programmatically pin a widget (no `requestPinAppWidget`).
  The **Add Widget** button shows instructions instead — the same fallback the
  Android app uses when pinning is unavailable.
- iOS has no VPN notification with Stop/Toggle actions, so the main screen's button
  becomes **Disconnect** while the proxy is running (the widget still toggles).
- The DNS proxy intercepts DNS only (UDP/TCP port 53) — regular traffic is not
  tunnelled. This is the intended native behavior for a DNS changer on iOS.
- If the chosen DNS server is unreachable, the proxy answers queries with
  SERVFAIL (fast failure) instead of letting them time out.
