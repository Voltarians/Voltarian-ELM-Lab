# ELM Lab 0.4.0 beta — compatibility evidence

Verified on November 24, 2026. This records selected behavior, not a certification of complete ELM327 conformance.

## Independent client

Client: python-OBD 0.7.3, source commit `a378bdd81d58c67d08050e4244173a9a7dbda73d`.
Transport: real loopback TCP sockets to the JVM harness. The harness uses the same `Elm327Engine` and `ElmStreamSession` classes as the Android TCP/Bluetooth service. No client or decoder patches were applied.

| Behavior | Result | Evidence scope |
| --- | --- | --- |
| Automatic CAN protocol connection | Passed | python-OBD connects and identifies protocol 6 |
| Explicit protocol connection/reconnect | Passed | ATTP6 initialization; shared vehicle state retained |
| Supported-PID discovery | Passed | Speed, RPM, and coolant advertised and queried |
| Live readings | Passed | 100 km/h, 2200 RPM, 90 °C, 13.8 V independently decoded |
| Fast queries | Passed | Response-count suffixes and repeated bare-CR requests |
| VIN ISO-TP reassembly | Passed | Client reconstructs all 17 VIN bytes correctly |
| VIN decoded string | Partial: known client issue | python-OBD 0.7.3 strips legitimate trailing ASCII 0/1/2 characters from its returned string; wire payload is correct |
| Stored and pending DTCs | Passed | P0300/P0171 and P0301 independently decoded |
| Permanent DTCs | Passed | P0300 decoded through the client's DTC decoder |
| Clear codes and readiness | Passed | Stored list empty, MIL off, count zero, monitors incomplete; permanent code retained |
| Fragmented TCP request | Passed | A request split across socket writes is assembled correctly |
| Separate adapter settings | Passed | Two simultaneous raw clients retain different echo/header settings |
| Android physical-phone TCP/SPP lifecycle | Not tested in this environment | Host tests do not verify Android permissions, radios, screen-off behavior, or OEM restrictions |
| Torque / Car Scanner / OBD Fusion / AndrOBD | Not tested | No compatibility claim yet |

Local validation also passed 40 unit tests and Android lint with zero errors. The APK build and signature verification passed.

The probe performs 30 checks, including repeated-query regression checks. Its report separately lists the VIN decoder limitation. Inspect `elm-core/build/reports/compatibility-probe.json` after running `tools/compatibility_probe.py`.

## Protocol references and local regression tests

The official [ELM327DSK datasheet](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf) is the command/framing reference:

- Pages 14–15: automatic CAN formatting and flow-control commands.
- Page 17: DLC display.
- Page 24: response suppression.
- Page 27: timeout setting.
- Page 35: CAN DTC count byte.
- Pages 42–43: padded CAN frames and VIN response segmentation.
- Page 53: timeout behavior and response-count suffixes.

Byte-expectation and state-transition tests cover initialization, DTC count/framing, CAF0 requests, padding/DLC, filters/masks, basic manual flow control, frame-count limits, multiple PIDs, reset/memory behavior, response suppression, timeout planning/backoff, clear-code side effects, and stream parsing.

Timing uses a documented deterministic simulation model. It does not reproduce the manufacturer's unpublished adaptive-timing implementation. Manual flow control currently supports STmin zero only. Outgoing segmented requests, extended addressing, other CAN rates/identifier widths, legacy protocols, and streaming bus monitoring are not implemented.

Protocol memory persists across resets within one connection; it is not stored across reconnects or app restarts.

## Reproduce

Use JDK 17+, the Android SDK, and the included Gradle wrapper:

```sh
./gradlew :elm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :elm-core:compatibilityClasspath
python -m pip install obd==0.7.3
python tools/compatibility_probe.py
```

For the exact audited client revision, install python-OBD from the commit above. It is an optional external development/test dependency, not part of the APK. The probe starts only a local test server and never connects to a vehicle.

## Physical-device validation still required

- Same-phone TCP client and second-device Wi-Fi client.
- Bluetooth pairing, permission denial/recovery, reconnect, and Bluetooth toggle.
- Screen-off operation, activity recreation, notification Stop, and closing active clients.
- Each named scanner's initialization and supported features on its exact app version.
- Comparison with real adapters and captured vehicle transactions.
