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

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "org.voltarians.elmlab.ios.network")

    func connect() {
        disconnect()
        guard let endpointPort = NWEndpoint.Port(port) else {
            state = .failed
            append("Invalid port")
            return
        }

        state = .connecting
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor in
                guard let self else { return }
                switch newState {
                case .ready:
                    self.state = .connected
                    self.append("Connected to \(self.host):\(self.port)")
                    self.receive()
                case .failed(let error):
                    self.state = .failed
                    self.append("Connection failed: \(error.localizedDescription)")
                case .cancelled:
                    self.state = .disconnected
                default:
                    break
                }
            }
        }
        connection.start(queue: queue)
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

    private func append(_ line: String) {
        guard !line.isEmpty else { return }
        transcript += transcript.isEmpty ? line : "\n\(line)"
    }
}

