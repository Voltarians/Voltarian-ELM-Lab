import Foundation
import Network

@MainActor
final class ELMSession: ObservableObject {
    enum State: String {
        case disconnected = "Disconnected"
        case connecting = "Connecting"
        case connected = "Connected"
        case failed = "Connection failed"
    }

    @Published var host = "192.168.1.100"
    @Published var port = "35000"
    @Published private(set) var state: State = .disconnected
    @Published private(set) var transcript = ""
    @Published private(set) var discoveredServers: [DiscoveredServer] = []
    @Published var speedKph = 0.0
    @Published var rpm = 0.0
    @Published var coolantC = 82.0
    @Published var supplyVoltage = 12.4

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "org.voltarians.elmlab.ios.network")
    private var browser: NWBrowser?

    struct DiscoveredServer: Identifiable, Hashable {
        let name: String
        let endpoint: NWEndpoint
        var id: String { "\(endpoint)" }
    }

    init() { startDiscovery() }

    func connect() {
        disconnect()
        guard let endpointPort = NWEndpoint.Port(port) else {
            state = .failed
            append("Invalid port")
            return
        }

        state = .connecting
        append("Connecting to \(host):\(port)")
        startConnection(NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp))
    }

    func connect(to server: DiscoveredServer) {
        disconnect()
        state = .connecting
        append("Connecting to \(server.name)")
        startConnection(NWConnection(to: server.endpoint, using: .tcp))
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        state = .disconnected
    }

    func send(_ rawCommand: String) {
        let command = rawCommand.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty, state == .connected else { return }
        append("> \(command)")
        connection?.send(content: Data("\(command)\r".utf8), completion: .contentProcessed { [weak self] error in
            if let error {
                Task { @MainActor in self?.append("Send failed: \(error.localizedDescription)") }
            }
        })
    }

    func runSmokeTest() {
        let commands = ["ATZ", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP6", "ATDP", "ATDPN", "ATRV", "0100", "0105", "010C", "010D", "0142", "0902"]
        Task {
            for command in commands {
                send(command)
                try? await Task.sleep(for: .milliseconds(180))
            }
        }
    }

    func applyProfile() {
        send("ATVSETSPD\(Int(speedKph))")
        send("ATVSETRPM\(Int(rpm))")
        send("ATVSETTEMP\(Int(coolantC))")
        send("ATVSETVOLT\(String(format: "%.1f", supplyVoltage))")
        send("ATVPROFILE")
    }

    func clearTranscript() { transcript = "" }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            Task { @MainActor in
                guard let self else { return }
                if let data, let text = String(data: data, encoding: .utf8) {
                    self.append(text.replacingOccurrences(of: "\r", with: "\n").trimmingCharacters(in: .newlines))
                }
                if let error { self.append("Receive failed: \(error.localizedDescription)") }
                if complete { self.disconnect() } else { self.receive() }
            }
        }
    }

    private func startDiscovery() {
        let browser = NWBrowser(for: .bonjour(type: "_voltarian-elm._tcp", domain: nil), using: .tcp)
        self.browser = browser
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let servers = results.compactMap { result -> DiscoveredServer? in
                guard case let .service(name, _, _, _) = result.endpoint else { return nil }
                return DiscoveredServer(name: name, endpoint: result.endpoint)
            }.sorted { $0.name < $1.name }
            Task { @MainActor in self?.discoveredServers = servers }
        }
        browser.start(queue: queue)
    }

    private func startConnection(_ connection: NWConnection) {
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor in
                guard let self else { return }
                switch newState {
                case .ready:
                    self.state = .connected
                    self.append("Connected")
                    self.receive()
                case .failed(let error):
                    self.state = .failed
                    self.append("Connection failed: \(error.localizedDescription)")
                case .cancelled: self.state = .disconnected
                default: break
                }
            }
        }
        connection.start(queue: queue)
    }

    private func append(_ line: String) {
        guard !line.isEmpty else { return }
        transcript += transcript.isEmpty ? line : "\n\(line)"
    }
}
