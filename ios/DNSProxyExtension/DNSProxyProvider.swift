import NetworkExtension
import Network

/// NEDNSProxyProvider that intercepts all DNS traffic on the device and forwards
/// it to the DNS servers configured in the app (providerConfiguration).
///
/// This is the native iOS equivalent of the Android app's DnsVpnService +
/// PacketForwarder DNS handling: hostnames are resolved to IPs before the proxy
/// becomes active, and every DNS query is transparently redirected to the custom
/// server (responses are written back as if they came from the original server).
final class DNSProxyProvider: NEDNSProxyProvider {

    /// Resolved DNS server IPs (primary first). Populated in startProxy before
    /// the proxy is active, so hostname resolution uses the normal system DNS
    /// (exactly like the Android app resolves before the tunnel is established).
    private var dnsServerIPs: [String] = []

    // MARK: - Lifecycle

    override func startProxy(options: [String: Any]? = nil, completionHandler: @escaping (Error?) -> Void) {
        let config = protocolConfiguration?.providerConfiguration ?? [:]
        let primaryRaw = (config[DNSChangerShared.primaryKey] as? String) ?? ""
        let secondaryRaw = (config[DNSChangerShared.secondaryKey] as? String) ?? ""

        var ips: [String] = []
        if let primary = Self.resolveServer(primaryRaw) {
            ips.append(primary)
        } else {
            // Mirror the Android fallback: unresolvable primary -> 1.1.1.1.
            ips.append(DNSChangerShared.defaultPrimary)
        }
        if let secondary = Self.resolveServer(secondaryRaw), !secondary.isEmpty {
            ips.append(secondary)
        }
        dnsServerIPs = ips

        completionHandler(nil)
    }

    override func stopProxy(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    // MARK: - Flows

    override func handleNewFlow(_ flow: NEAppProxyFlow) -> Bool {
        if let udpFlow = flow as? NEAppProxyUDPFlow {
            Task { await handleUDPFlow(udpFlow) }
            return true
        }
        if let tcpFlow = flow as? NEAppProxyTCPFlow {
            Task { await handleTCPFlow(tcpFlow) }
            return true
        }
        return false
    }

    // MARK: - UDP

    private func handleUDPFlow(_ flow: NEAppProxyUDPFlow) async {
        do {
            try await flow.open()
        } catch {
            flow.close(with: error)
            return
        }
        await readUDPFlow(flow)
    }

    private func readUDPFlow(_ flow: NEAppProxyUDPFlow) async {
        while true {
            do {
                let (datagrams, endpoints) = try await flow.readDatagrams()
                let servers = dnsServerIPs
                for (index, query) in datagrams.enumerated() {
                    let originalEndpoint = endpoints.indices.contains(index) ? endpoints[index] : nil
                    Task {
                        await self.forwardUDPQuery(query,
                                                   flow: flow,
                                                   originalEndpoint: originalEndpoint,
                                                   servers: servers)
                    }
                }
            } catch {
                // The client closed the flow. Give in-flight queries a short
                // grace period so their responses can still be written back.
                try? await Task.sleep(nanoseconds: 300_000_000)
                flow.close(with: nil)
                return
            }
        }
    }

    private func forwardUDPQuery(_ query: Data,
                                 flow: NEAppProxyUDPFlow,
                                 originalEndpoint: NWEndpoint?,
                                 servers: [String]) async {
        let server = servers.first ?? DNSChangerShared.defaultPrimary
        let endpoint = originalEndpoint ?? NWHostEndpoint(hostname: server, port: "53")
        guard let port = NWEndpoint.Port(rawValue: 53) else { return }

        let connection = NWConnection(host: .init(server), port: port, using: .udp)

        let ready = await connection.startAndWait(timeout: 3)
        guard ready else {
            connection.cancel()
            await Self.writeFallback(query, flow: flow, endpoint: endpoint)
            return
        }

        let sent = await connection.sendData(query, timeout: 3)
        guard sent else {
            connection.cancel()
            await Self.writeFallback(query, flow: flow, endpoint: endpoint)
            return
        }

        let response = await connection.receiveDatagram(timeout: 3)
        if let response {
            try? await flow.writeDatagrams([response], sentBy: [endpoint])
        } else {
            await Self.writeFallback(query, flow: flow, endpoint: endpoint)
        }
        connection.cancel()
    }

    /// Writes a SERVFAIL reply when the custom DNS server cannot be reached, so
    /// the client fails fast instead of waiting for a timeout.
    private static func writeFallback(_ query: Data, flow: NEAppProxyUDPFlow, endpoint: NWEndpoint) async {
        try? await flow.writeDatagrams([servfailResponse(for: query)], sentBy: [endpoint])
    }

    // MARK: - TCP

    private func handleTCPFlow(_ flow: NEAppProxyTCPFlow) async {
        do {
            try await flow.open()
        } catch {
            flow.close(with: error)
            return
        }

        let server = dnsServerIPs.first ?? DNSChangerShared.defaultPrimary
        guard let port = NWEndpoint.Port(rawValue: 53) else { return }
        let connection = NWConnection(host: .init(server), port: port, using: .tcp)

        let ready = await connection.startAndWait(timeout: 3)
        guard ready else {
            connection.cancel()
            flow.close(with: nil)
            return
        }
        await Self.pipe(flow: flow, connection: connection)
    }

    /// Bidirectional TCP pipe: client <-> custom DNS server.
    private static func pipe(flow: NEAppProxyTCPFlow, connection: NWConnection) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                // client -> DNS server
                while true {
                    guard let data = await Self.readFlowData(flow) else { break }
                    if data.isEmpty { break }
                    guard await connection.sendData(data, timeout: 10) else { break }
                }
                connection.cancel()
            }
            group.addTask {
                // DNS server -> client
                while true {
                    guard let data = await connection.receiveData(maxLength: 4096, timeout: 10) else { break }
                    if data.isEmpty { break }
                    guard await Self.writeFlowData(data, to: flow) else { break }
                }
                flow.close(with: nil)
            }
        }
    }

    private static func readFlowData(_ flow: NEAppProxyTCPFlow) async -> Data? {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            flow.readData { data, error in
                if let data = data, !data.isEmpty, error == nil {
                    box.once { continuation.resume(returning: data) }
                } else {
                    box.once { continuation.resume(returning: nil) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 30) {
                box.once { continuation.resume(returning: nil) }
            }
        }
    }

    private static func writeFlowData(_ data: Data, to flow: NEAppProxyTCPFlow) async -> Bool {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            flow.write(data) { error in
                box.once { continuation.resume(returning: error == nil) }
            }
        }
    }

    // MARK: - DNS helpers

    /// Builds a minimal SERVFAIL response (header only, same ID and question
    /// count as the query, RCODE = 2).
    private static func servfailResponse(for query: Data) -> Data {
        guard query.count >= 12 else { return query }
        var response = Data(query.prefix(12))
        response[2] = 0x81 // QR=1, RD copied as set
        response[3] = 0x82 // RA=1, RCODE=2 (SERVFAIL)
        response.replaceSubrange(6..<12, with: Data([0, 0, 0, 0, 0, 0]))
        return response
    }

    /// Converts a user-entered DNS server setting (IP literal or hostname) into
    /// an IP string, mirroring the Android app's resolveDnsToIp(): strips URL
    /// schemes and paths, resolves hostnames via the system DNS, prefers IPv4.
    private static func resolveServer(_ setting: String) -> String? {
        let s = normalize(setting)
        if s.isEmpty { return nil }
        if isIPLiteral(s) { return s }
        return resolveHostname(s)
    }

    private static func normalize(_ s: String) -> String {
        var out = s
        if let range = out.range(of: "://") {
            out = String(out[range.upperBound...])
        }
        if let slash = out.firstIndex(of: "/") {
            out = String(out[..<slash])
        }
        return out
    }

    private static func isIPLiteral(_ s: String) -> Bool {
        // IPv6 (contains ':' and is not a URL)
        if s.contains(":") && !s.contains("://") { return true }
        return isIPv4(s)
    }

    private static func isIPv4(_ s: String) -> Bool {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.allSatisfy({ $0.isNumber }) else { return false }
            guard let value = Int(part), value <= 255 else { return false }
        }
        return true
    }

    private static func resolveHostname(_ host: String) -> String? {
        var hints = addrinfo(
            ai_flags: 0,
            ai_family: AF_INET,
            ai_socktype: SOCK_DGRAM,
            ai_protocol: 0,
            ai_addrlen: 0,
            ai_addr: nil,
            ai_canonname: nil,
            ai_next: nil
        )
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else {
            if result != nil { freeaddrinfo(result) }
            return nil
        }
        defer { freeaddrinfo(first) }
        var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(first.pointee.ai_addr, first.pointee.ai_addrlen,
                          &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else {
            return nil
        }
        return String(cString: buffer)
    }
}

// MARK: - Helpers

private final class ResumeBox {
    private let lock = NSLock()
    private var resumed = false

    func once(_ action: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !resumed else { return }
        resumed = true
        action()
    }
}

extension NEAppProxyFlow {
    /// Closes both read and write sides of the flow.
    func close(with error: Error?) {
        closeReadWithError(error)
        closeWriteWithError(error)
    }
}

private extension NWConnection {
    /// Starts the connection and waits until it is ready (or fails/times out).
    func startAndWait(timeout: TimeInterval) async -> Bool {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            stateUpdateHandler = { state in
                switch state {
                case .ready:
                    box.once { continuation.resume(returning: true) }
                case .failed, .cancelled:
                    box.once { continuation.resume(returning: false) }
                default:
                    break
                }
            }
            start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                box.once {
                    continuation.resume(returning: false)
                    self.cancel()
                }
            }
        }
    }

    /// Sends a message; returns false on failure or timeout.
    func sendData(_ data: Data, timeout: TimeInterval) async -> Bool {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            send(content: data, completion: .contentProcessed { error in
                box.once { continuation.resume(returning: error == nil) }
            })
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                box.once {
                    continuation.resume(returning: false)
                    self.cancel()
                }
            }
        }
    }

    /// Receives one UDP datagram; returns nil on failure or timeout.
    func receiveDatagram(timeout: TimeInterval) async -> Data? {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            receiveMessage { data, _, _, error in
                if let data = data, error == nil {
                    box.once { continuation.resume(returning: data) }
                } else {
                    box.once { continuation.resume(returning: nil) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                box.once {
                    continuation.resume(returning: nil)
                    self.cancel()
                }
            }
        }
    }

    /// Receives up to `maxLength` bytes (TCP); returns nil on failure or timeout.
    func receiveData(maxLength: Int, timeout: TimeInterval) async -> Data? {
        await withCheckedContinuation { continuation in
            let box = ResumeBox()
            receive(minimumIncompleteLength: 1, maximumLength: maxLength) { data, _, _, error in
                if let data = data, !data.isEmpty, error == nil {
                    box.once { continuation.resume(returning: data) }
                } else {
                    box.once { continuation.resume(returning: nil) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                box.once { continuation.resume(returning: nil) }
            }
        }
    }
}
