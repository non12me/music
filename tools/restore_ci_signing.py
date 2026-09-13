from pathlib import Path
import base64
import json
import os

root = Path(__file__).resolve().parents[1]
key = "".join(os.environ.get("PULSE_KEYSTORE", "").split())
password = os.environ.get("PULSE_KEY_PASSWORD", "").strip()
if not key or not password:
    raise SystemExit("Faltan PULSE_KEYSTORE y PULSE_KEY_PASSWORD. Sigue EMPEZAR.md antes de compilar.")
private = root / ".private"
private.mkdir(mode=0o700, exist_ok=True)
(private / "pulse.jks").write_bytes(base64.b64decode(key, validate=True))
(private / "signing.json").write_text(json.dumps({"alias": "pulse", "password": password}))
for path in private.iterdir():
    path.chmod(0o600)
print("Firma privada preparada; no se imprimieron credenciales.")
