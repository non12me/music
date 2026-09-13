#!/usr/bin/env python3
"""Stage an official stable release and apply Pulse only if all compatibility checks pass."""
from pathlib import Path, PurePosixPath
import hashlib
import io
import json
import re
import shutil
import stat
import tempfile
import urllib.request
import zipfile
from apply_pulse import ROOT, apply

REPOSITORY = "MetrolistGroup/Metrolist"

def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Pulse-Music-Personal-Build", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=120) as response:
        raw = response.read(100 * 1024 * 1024 + 1)
        if len(raw) > 100 * 1024 * 1024:
            raise ValueError("Descarga mayor que el límite de seguridad.")
        return raw

def safe_extract(raw: bytes, destination: Path):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        members = archive.infolist()
        if sum(m.file_size for m in members) > 300 * 1024 * 1024:
            raise ValueError("Archivo fuente demasiado grande.")
        for member in members:
            path = PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in member.filename or stat.S_ISLNK(member.external_attr >> 16):
                raise ValueError("Ruta insegura en el archivo fuente.")
        archive.extractall(destination)

def prepare():
    release = json.loads(get(f"https://api.github.com/repos/{REPOSITORY}/releases/latest"))
    tag = release["tag_name"]
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v?\d+\.\d+\.\d+", tag):
        raise ValueError("La versión recibida no es una publicación estable.")
    commit = json.loads(get(f"https://api.github.com/repos/{REPOSITORY}/commits/{tag}"))["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Identificador de versión no válido.")
    raw = get(f"https://api.github.com/repos/{REPOSITORY}/zipball/{commit}")
    backup = ROOT / "android.previous"
    if backup.exists():
        raise ValueError("Ya existe android.previous. Conserva o mueve esa copia antes de actualizar otra vez.")
    with tempfile.TemporaryDirectory(prefix="pulse-stage-", dir=ROOT) as directory:
        stage = Path(directory)
        safe_extract(raw, stage)
        roots = [p for p in stage.iterdir() if p.is_dir()]
        if len(roots) != 1:
            raise ValueError("Estructura inesperada del archivo fuente.")
        candidate = roots[0]
        # The backup format must be reviewed if upstream adds/removes entities.
        snapshots = list((candidate / "app/schemas").rglob("*.json"))
        latest_schema = max(snapshots, key=lambda p: int(p.stem))
        schema = json.loads(latest_schema.read_text())["database"]
        known = {"song", "artist", "album", "playlist", "podcast", "song_artist_map", "song_album_map", "album_artist_map",
                 "playlist_song_map", "search_history", "format", "lyrics", "event", "related_song_map", "set_video_id",
                 "playCount", "recognition_history", "speed_dial_item"}
        if {e["tableName"] for e in schema["entities"]} != known:
            raise ValueError("La nueva base cambió las tablas. Se requiere revisar la copia de biblioteca antes de compilar.")
        apply(candidate)
        current = ROOT / "android"
        current.rename(backup)
        try:
            shutil.move(str(candidate), str(current))
        except Exception:
            backup.rename(current)
            raise
    lock = {"repository": REPOSITORY, "commit": commit, "tag": tag,
            "archiveSha256": hashlib.sha256(raw).hexdigest(), "sourceLicense": "GPL-3.0",
            "pulseApplicationId": "com.brandon.pulse.music"}
    (ROOT / "upstream.lock.json").write_text(json.dumps(lock, indent=2) + "\n")
    print(f"Base preparada: {tag} ({commit[:12]}). Falta compilar y probar en el teléfono.")

if __name__ == "__main__":
    prepare()
