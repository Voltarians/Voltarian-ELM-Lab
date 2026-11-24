#!/usr/bin/env python3
"""Run python-OBD as an independent client against the shared ELM Lab stream engine.

Requirements: pip install obd==0.7.3
First run: ./gradlew :elm-core:compatibilityClasspath
No vehicle or Android device is accessed. Results do not establish phone/SPP compatibility.
"""
import json
import logging
from pathlib import Path
import socket
import subprocess
import sys
import time

import obd
from obd import OBDCommand
from obd.decoders import dtc

ROOT = Path(__file__).resolve().parents[1]
checks = []
limitations = []


def check(name, condition, detail=""):
    checks.append({"name": name, "passed": bool(condition), "detail": str(detail)})
    if not condition:
        raise AssertionError(f"{name}: {detail}")


def quantity(connection, command, expected, unit):
    response = connection.query(command, force=True)
    check(command.name, not response.is_null() and abs(response.value.to(unit).magnitude - expected) < 0.01, response.value)


def run():
    classpath = (ROOT / "elm-core/build/compatibility-classpath.txt").read_text().strip()
    logging.getLogger("obd").setLevel(logging.ERROR)
    proc = subprocess.Popen(["java", "-cp", classpath, "org.voltarians.elmlab.CompatibilityServerKt", "0"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    connection = None
    try:
        line = proc.stdout.readline().strip()
        if not line.startswith("PORT="):
            raise RuntimeError("Compatibility server did not start: " + line)
        port = int(line.split("=", 1)[1])
        endpoint = f"socket://127.0.0.1:{port}"
        connection = obd.OBD(endpoint, baudrate=38400, fast=True, timeout=2)
        check("automatic protocol connection", connection.is_connected(), connection.status())
        check("detected CAN protocol", connection.protocol_id() == "6", connection.protocol_id())
        for command in [obd.commands.SPEED, obd.commands.RPM, obd.commands.COOLANT_TEMP]:
            check("advertised " + command.name, connection.supports(command))
        quantity(connection, obd.commands.SPEED, 100, "kph")
        quantity(connection, obd.commands.RPM, 2200, "rpm")
        quantity(connection, obd.commands.COOLANT_TEMP, 90, "degC")
        quantity(connection, obd.commands.CONTROL_MODULE_VOLTAGE, 13.8, "volt")
        # Repeated commands exercise python-OBD's bare-CR optimization and response-count suffix.
        for i in range(5):
            quantity(connection, obd.commands.SPEED, 100, "kph")
        response = connection.query(obd.commands.VIN, force=True)
        vin = response.value.decode() if isinstance(response.value, (bytes, bytearray)) else response.value
        wire_vin = bytes(response.messages[0].data[3:20]).decode("ascii")
        check("multi-frame VIN reassembly", wire_vin == "TESTCAR0000000001", wire_vin)
        if vin != wire_vin:
            # python-OBD 0.7.3 decode_encoded_string uses strip() with a character set
            # containing ASCII 0/1/2; it removes legitimate trailing VIN characters.
            limitations.append({"feature": "python-OBD VIN string decoder", "wire_value": wire_vin, "decoded_value": str(vin), "reason": "Client strips trailing ASCII 0/1/2; wire reassembly is correct. See decoders.py decode_encoded_string."})
        response = connection.query(obd.commands.GET_DTC)
        check("stored CAN DTC count/decoding", [code for code, _ in response.value] == ["P0300", "P0171"], response.value)
        response = connection.query(obd.commands.GET_CURRENT_DTC)
        check("pending CAN DTC decoding", [code for code, _ in response.value] == ["P0301"], response.value)
        status = connection.query(obd.commands.STATUS).value
        check("MIL on before clear", status.MIL is True, status.MIL)
        check("DTC count before clear", status.DTC_count == 2, status.DTC_count)
        permanent = OBDCommand("PERMANENT_TEST", "Permanent DTCs", b"0A", 0, dtc, fast=False)
        response = connection.query(permanent, force=True)
        check("permanent DTC decoding", [code for code, _ in response.value] == ["P0300"], response.value)
        connection.query(obd.commands.CLEAR_DTC)
        response = connection.query(obd.commands.GET_DTC)
        check("clear DTC empties stored list", response.value == [], response.value)
        status = connection.query(obd.commands.STATUS).value
        check("MIL off after clear", status.MIL is False, status.MIL)
        check("DTC count zero after clear", status.DTC_count == 0, status.DTC_count)
        check("monitors incomplete after clear", not status.MISFIRE_MONITORING.complete)
        response = connection.query(permanent, force=True)
        check("clear preserves permanent code", [code for code, _ in response.value] == ["P0300"], response.value)
        connection.close()
        connection = obd.OBD(endpoint, baudrate=38400, protocol="6", fast=False, timeout=2)
        check("explicit protocol reconnect", connection.is_connected(), connection.status())
        quantity(connection, obd.commands.SPEED, 100, "kph")
        check("vehicle state survives reconnect", connection.query(obd.commands.GET_DTC).value == [])
        # Raw clients test packet boundaries and two independent adapter sessions.
        with socket.create_connection(("127.0.0.1", port), timeout=2) as a, socket.create_connection(("127.0.0.1", port), timeout=2) as b:
            def query(sock, data):
                sock.sendall(data)
                received = b""
                while not received.endswith(b">"):
                    chunk = sock.recv(4096)
                    if not chunk:
                        raise RuntimeError("Connection closed before prompt")
                    received += chunk
                return received.decode("ascii")
            query(a, b"ATE0\r"); query(a, b"ATSP6\r"); query(a, b"ATH1\r")
            a.sendall(b"01")
            check("fragmented request", query(a, b"0D1\r") == "7E8 03 41 0D 64 00 00 00 00\r>")
            query(b, b"ATSP6\r")
            check("independent echo/header settings", query(b, b"010D1\r") == "010D1\r41 0D 64\r>")
        return {"client": "python-OBD " + str(obd.__version__), "transport": "loopback TCP using shared ElmStreamSession", "checks": checks, "limitations": limitations, "passed": True}
    finally:
        if connection is not None:
            connection.close()
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill(); proc.wait()


if __name__ == "__main__":
    started = time.monotonic()
    try:
        result = run()
    except Exception as error:
        result = {"passed": False, "error": repr(error), "checks": checks}
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    target = ROOT / "elm-core/build/reports/compatibility-probe.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["passed"] else 1)
