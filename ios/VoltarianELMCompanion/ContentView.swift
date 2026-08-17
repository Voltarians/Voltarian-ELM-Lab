import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var session: ELMSession
    @State private var command = "ATI"

    var body: some View {
        NavigationStack {
            Form {
                Section("Android emulator") {
                    TextField("Host or IP address", text: $session.host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Port", text: $session.port)
                        .keyboardType(.numberPad)
                    HStack {
                        Circle()
                            .fill(session.state == .connected ? .green : .secondary)
                            .frame(width: 10, height: 10)
                        Text(session.state.rawValue)
                        Spacer()
                        if session.state == .connected {
                            Button("Disconnect") { session.disconnect() }
                        } else {
                            Button("Connect") { session.connect() }
                        }
                    }
                }

                Section("Command") {
                    HStack {
                        TextField("ELM or OBD-II command", text: $command)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .onSubmit { session.send(command) }
                        Button("Send") { session.send(command) }
                            .disabled(session.state != .connected)
                    }
                    Button("Run adapter smoke test") { session.runSmokeTest() }
                        .disabled(session.state != .connected)
                }

                Section {
                    ScrollView {
                        Text(session.transcript.isEmpty ? "No traffic yet" : session.transcript)
                            .font(.system(.caption, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(minHeight: 260)
                    Button("Clear transcript", role: .destructive) { session.clearTranscript() }
                } header: {
                    Text("Transcript")
                }
            }
            .navigationTitle("ELM Companion")
        }
    }
}

