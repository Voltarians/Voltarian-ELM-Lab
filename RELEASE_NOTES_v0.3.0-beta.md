# ELM Lab v0.3.0 beta

General-purpose Android OBD-II simulator foundation, independent of vehicle make.

Adds generic scenarios, editable vehicle readings, DTC/readiness/freeze-frame simulation, saved JSON profiles, response fault injection, custom response sequences, recorded-response export, and local command logs. Moves connections into a foreground service and isolates adapter settings per client. Corrects PID advertising, rejects unsupported AT commands, implements CAN response PCI framing, and closes connected clients on stop.

This beta does not include billing or production store signing. Review README protocol coverage and replay limits before compatibility testing. TCP and Bluetooth hardware behavior still require testing on physical phones.
