# ELM Lab v0.4.0 beta

Protocol accuracy and independent-client validation milestone.

- Fix CAN DTC count bytes for stored, pending, and permanent codes.
- Pad displayed CAN frames to eight data bytes; add optional DLC display.
- Add CAF0/1, CFC0/1, D0/1, R0/1, AL/NL, M0/1, PC, AR, AT0/1/2, ST, TP6, CF/CM, and wildcard CRA behavior.
- Parse response-count suffixes and multiple-PID requests.
- Simulate timeout limits and deterministic adaptive-timeout learning/backoff.
- Support basic raw-mode single-frame requests and manual flow-control CTS/block size, WAIT, and overflow. Nonzero STmin remains unsupported and returns an error.
- Preserve delivered clear-code effects even when replies are suppressed, filtered, or late.
- Share command-stream parsing between Android TCP/SPP and the host compatibility harness.
- Add independent python-OBD TCP probes and a compatibility matrix.

Only CAN 11-bit / 500 kbit/s is implemented. This release does not establish full ELM327 conformance or verify physical-phone Bluetooth behavior. See docs/COMPATIBILITY.md for scope and known client limitations.
