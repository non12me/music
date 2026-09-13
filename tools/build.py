#!/usr/bin/env python3
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from apply_pulse import apply
from setup_signing import setup

ROOT = Path(__file__).resolve().parents[1]

def build(source: Path, variant: str, test: bool):
    apply(source)
    setup()
    signing = json.loads((ROOT / ".private/signing.json").read_text())
    env = os.environ.copy()
    env.update({"METROLIST_APPLICATION_ID": "com.brandon.pulse.music", "METROLIST_APP_NAME": "Pulse Music",
        "METROLIST_DEBUG_KEYSTORE_PATH": str(ROOT / ".private/pulse.jks"),
        "METROLIST_DEBUG_KEYSTORE_PASSWORD": signing["password"], "METROLIST_DEBUG_KEY_ALIAS": "pulse",
        "METROLIST_DEBUG_KEY_PASSWORD": signing["password"], "PULSE_SIGN_PASSWORD": signing["password"]})
    wrapper = source / ("gradlew.bat" if os.name == "nt" else "gradlew")
    wrapper.chmod(wrapper.stat().st_mode | 0o111)
    commands = [str(wrapper), "--console=plain", "--no-configuration-cache", f":app:assembleFoss{variant.title()}"]
    if test:
        commands += [":app:testFossDebugUnitTest", "--tests", "com.metrolist.music.pulse.*"]
    subprocess.run(commands, cwd=source, env=env, check=True)
    apks = list((source / f"app/build/outputs/apk/foss/{variant}").glob("*.apk"))
    if len(apks) != 1:
        raise RuntimeError(f"Se esperaba un APK; se encontraron {len(apks)}. No se elegirá uno al azar.")
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    target = dist / "Pulse-Music.apk"
    sdk = Path(env.get("ANDROID_HOME", env.get("ANDROID_SDK_ROOT", "")))
    executables = sorted((sdk / "build-tools").glob("*/apksigner*"), reverse=True) if str(sdk) != "." else []
    if variant == "release":
        if not executables:
            raise RuntimeError("Define ANDROID_HOME apuntando al SDK con Android Build Tools instalado.")
        subprocess.run([str(executables[0]), "sign", "--ks", str(ROOT / ".private/pulse.jks"),
            "--ks-key-alias", "pulse", "--ks-pass", "env:PULSE_SIGN_PASSWORD", "--key-pass", "env:PULSE_SIGN_PASSWORD",
            "--out", str(target), str(apks[0])], env=env, check=True)
    else:
        shutil.copy2(apks[0], target)
    if executables:
        subprocess.run([str(executables[0]), "verify", "--verbose", str(target)], check=True)
    source_build = (source / "app/build.gradle.kts").read_text()
    metadata = {
        "applicationId": "com.brandon.pulse.music", "variant": variant,
        "baseVersion": re.search(r'versionName = "([^"]+)"', source_build).group(1),
        "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "source": json.loads((ROOT / "upstream.lock.json").read_text()),
        "devicePlaybackTested": False,
    }
    (dist / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"APK compilado y firmado: {target}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "android")
    parser.add_argument("--variant", choices=["debug", "release"], default="release")
    parser.add_argument("--skip-tests", action="store_true")
    args = parser.parse_args()
    try:
        build(args.source.resolve(), args.variant, not args.skip_tests)
    except (RuntimeError, subprocess.CalledProcessError, ValueError, OSError) as error:
        print(f"No se generó un APK validado: {error}", file=sys.stderr)
        raise SystemExit(1)
