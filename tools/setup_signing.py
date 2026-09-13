#!/usr/bin/env python3
"""Create a permanent signing identity. Never regenerate it when updating an installed app."""
from pathlib import Path
import base64
import json
import os
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def setup():
    private = ROOT / ".private"
    private.mkdir(exist_ok=True, mode=0o700)
    key = private / "pulse.jks"
    config = private / "signing.json"
    if key.exists() and config.exists():
        print("Firma existente conservada.")
        return
    if key.exists() or config.exists():
        raise RuntimeError("Firma incompleta. Recupera los archivos originales; no generes otra clave.")
    keytool = shutil.which("keytool")
    if not keytool:
        raise RuntimeError("Falta JDK 21 (keytool). En Codespaces puedes instalarlo o ejecutar el flujo Setup signing.")
    password = secrets.token_urlsafe(32)
    env = os.environ.copy()
    env["PULSE_NEW_KEY_PASSWORD"] = password
    temp_key = private / "pulse.new.jks"
    subprocess.run([keytool, "-genkeypair", "-keystore", str(temp_key), "-storetype", "PKCS12",
        "-storepass:env", "PULSE_NEW_KEY_PASSWORD", "-keypass:env", "PULSE_NEW_KEY_PASSWORD",
        "-alias", "pulse", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=Pulse Music Personal", "-noprompt"], env=env, check=True, capture_output=True)
    temp_key.replace(key)
    config.write_text(json.dumps({"alias": "pulse", "password": password}, indent=2) + "\n")
    (private / "PULSE_KEYSTORE_BASE64.txt").write_text(base64.b64encode(key.read_bytes()).decode() + "\n")
    (private / "PULSE_KEY_PASSWORD.txt").write_text(password + "\n")
    for path in private.iterdir():
        path.chmod(0o600)
    print("Firma creada en .private/. Guarda esta carpeta de forma privada; no la subas al repositorio.")

if __name__ == "__main__":
    setup()
