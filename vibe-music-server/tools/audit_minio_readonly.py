"""Read-only MySQL / Redis / MinIO reconciliation using Python's standard library.

Run from any directory: python audit_minio_readonly.py --output <report-directory>
Requires mysql on PATH. Reads local application.yml and .env; never writes remote
data, downloads object bodies, or prints credentials. No delete mode exists.
"""
import argparse
import collections
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import urllib.error
import urllib.parse as url
import urllib.request
import xml.etree.ElementTree as ET


SERVER = Path(__file__).resolve().parents[1]
ROOT = SERVER.parent
TZ = dt.timezone(dt.timedelta(hours=8))
MEDIA = {
    "tb_song": ["cover_url", "audio_url"], "tb_album": ["cover_url"],
    "tb_playlist": ["cover_url"], "tb_artist": ["avatar"],
    "tb_user": ["user_avatar"], "tb_banner": ["banner_url"],
}


def configuration():
    env = {}
    for line in (SERVER / ".env").read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    env.update(os.environ)
    app = (SERVER / "src/main/resources/application.yml").read_text(encoding="utf-8")
    def literal(key):
        match = re.search(r"^\s*" + re.escape(key) + r":\s*([^#\r\n]+)", app, re.M)
        if not match:
            raise RuntimeError("Missing configuration field: " + key)
        value = match.group(1).strip().strip('"\'')
        if "${" in value:
            raise RuntimeError("Provide an explicit environment override for " + key)
        return value
    jdbc = env.get("SPRING_DATASOURCE_URL") or literal("url")
    database = url.urlsplit(jdbc.removeprefix("jdbc:"))
    return {
        "endpoint": env.get("MINIO_ENDPOINT") or literal("endpoint"),
        "bucket": env.get("MINIO_BUCKET") or literal("bucket"),
        "access": env["MINIO_ACCESS_KEY"], "secret": env["MINIO_SECRET_KEY"],
        "db_host": database.hostname, "db_port": database.port or 3306,
        "database": database.path.lstrip("/"),
        "db_user": env.get("SPRING_DATASOURCE_USERNAME", env.get("MYSQL_USERNAME", "root")),
        "db_password": env.get("SPRING_DATASOURCE_PASSWORD", env.get("MYSQL_PASSWORD", "")),
        "redis_host": env.get("SPRING_DATA_REDIS_HOST", literal("host")),
        "redis_port": int(env.get("SPRING_DATA_REDIS_PORT", "6379")),
        "redis_database": int(env.get("SPRING_DATA_REDIS_DATABASE", literal("database"))),
        "redis_password": env.get("SPRING_DATA_REDIS_PASSWORD", ""),
    }


def mysql(cfg, statements):
    if any(not q.lstrip().upper().startswith("SELECT ") for q in statements):
        raise ValueError("Only SELECT statements are allowed")
    query = "SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT; "
    query += "; ".join(statements) + "; COMMIT;"
    env = os.environ.copy()
    env["MYSQL_PWD"] = cfg["db_password"]
    args = ["mysql", "--host=" + cfg["db_host"], "--port=" + str(cfg["db_port"]),
            "--connect-timeout=5", "--user=" + cfg["db_user"], "--database=" + cfg["database"],
            "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4"]
    result = subprocess.run(args, input=query, capture_output=True, text=True,
                            encoding="utf-8", env=env, timeout=60)
    if result.returncode:
        code = re.search(r"ERROR (\d+)", result.stderr)
        raise RuntimeError("MySQL read failed (" + (code.group(1) if code else "connection/query") + ")")
    return [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]


def s3_get(cfg, params):
    endpoint = url.urlsplit(cfg["endpoint"])
    path = endpoint.path.rstrip("/") + "/" + url.quote(cfg["bucket"], safe="")
    quote = lambda s: url.quote(str(s), safe="-_.~")
    query = "&".join(quote(k) + "=" + quote(v) for k, v in sorted(params.items()))
    now = dt.datetime.now(dt.timezone.utc)
    stamp, date = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    empty_hash = hashlib.sha256(b"").hexdigest()
    headers = "host:" + endpoint.netloc + "\nx-amz-content-sha256:" + empty_hash + "\nx-amz-date:" + stamp + "\n"
    signed = "host;x-amz-content-sha256;x-amz-date"
    canonical = "\n".join(["GET", path, query, headers, signed, empty_hash])
    scope = date + "/us-east-1/s3/aws4_request"
    string_to_sign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + hashlib.sha256(canonical.encode()).hexdigest()
    key = ("AWS4" + cfg["secret"]).encode()
    for part in (date, "us-east-1", "s3", "aws4_request"):
        key = hmac.new(key, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    request = urllib.request.Request(url.urlunsplit((endpoint.scheme, endpoint.netloc, path, query, "")), method="GET")
    request.add_header("x-amz-date", stamp)
    request.add_header("x-amz-content-sha256", empty_hash)
    request.add_header("Authorization", "AWS4-HMAC-SHA256 Credential=" + cfg["access"] + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature)
    # Never forward credentials on redirects or to an environment proxy.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=30) as response:
            return ET.fromstring(response.read())
    except urllib.error.HTTPError as exc:
        raise RuntimeError("MinIO read failed, HTTP " + str(exc.code)) from None


def inventory(cfg):
    objects, params = {}, {"list-type": "2", "encoding-type": "url"}
    while True:
        root = s3_get(cfg, params)
        for item in root.findall("{*}Contents"):
            # MinIO's list encoding uses '+' for spaces and '%2B' for literal '+'.
            # This applies ONLY to list XML, never to a stored media URL path.
            key = url.unquote_plus(item.findtext("{*}Key"))
            objects[key] = {"key": key, "bytes": int(item.findtext("{*}Size")),
                            "last_modified": item.findtext("{*}LastModified")}
        if root.findtext("{*}IsTruncated") != "true":
            break
        params["continuation-token"] = root.findtext("{*}NextContinuationToken")
    versioning = s3_get(cfg, {"versioning": ""}).findtext("{*}Status") or "Disabled"
    return objects, versioning


class RedisReadOnly:
    def __init__(self, cfg):
        self.socket = socket.create_connection((cfg["redis_host"], cfg["redis_port"]), timeout=5)
        self.stream = self.socket.makefile("rb")
        if cfg["redis_password"]:
            self.command("AUTH", cfg["redis_password"])
        self.command("SELECT", cfg["redis_database"])

    def command(self, *args):
        if args[0] not in {"AUTH", "SELECT", "SCAN", "GET", "TTL", "ZRANGE"}:
            raise ValueError("Redis command not permitted")
        parts = [str(arg).encode() for arg in args]
        self.socket.sendall(b"*%d\r\n" % len(parts) + b"".join(b"$%d\r\n" % len(p) + p + b"\r\n" for p in parts))
        return self.read()

    def read(self):
        line = self.stream.readline()
        kind, value = line[:1], line[1:-2]
        if kind == b"+": return value.decode()
        if kind == b"-": raise RuntimeError("Redis read rejected")
        if kind == b":": return int(value)
        if kind == b"$":
            size = int(value)
            if size < 0: return None
            data = self.stream.read(size)
            if self.stream.read(2) != b"\r\n": raise RuntimeError("Invalid Redis response")
            return data.decode("utf-8")
        if kind == b"*": return [self.read() for _ in range(int(value))]
        raise RuntimeError("Invalid Redis response")

    def sessions(self):
        sessions = []
        for prefix in ("song-upload:session:", "album-upload:session:"):
            cursor = "0"
            while True:
                cursor, keys = self.command("SCAN", cursor, "MATCH", prefix + "*", "COUNT", 100)
                for key in keys:
                    value = self.command("GET", key)
                    if value is not None:
                        sessions.append({"type": prefix.split(":")[0], "upload_id": key[len(prefix):],
                                         "ttl_seconds": self.command("TTL", key), "payload": value})
                if cursor == "0": break
        index = self.command("ZRANGE", "song-upload:session-index", 0, -1, "WITHSCORES")
        return sessions, dict(zip(index[::2], index[1::2]))

    def close(self):
        self.stream.close()
        self.socket.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cfg = configuration()
    started = dt.datetime.now(TZ).isoformat()
    objects, versioning = inventory(cfg)
    tables = mysql(cfg, ["SELECT JSON_OBJECT('table', TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"])
    table_names = {x["table"] for x in tables}
    statements = []
    for table, columns in MEDIA.items():
        for col in columns:
            statements.append(f"SELECT JSON_OBJECT('table','{table}','id',id,'column','{col}','value',`{col}`) FROM `{table}` WHERE `{col}` IS NOT NULL AND `{col}`<>''")
    cleanup_present = "tb_account_cleanup_task" in table_names
    if cleanup_present:
        statements.append("SELECT JSON_OBJECT('table','tb_account_cleanup_task','id',id,'column','resource_ref','value',resource_ref,'status',status) FROM tb_account_cleanup_task WHERE resource_type='MINIO_AVATAR' AND status<>'DONE'")
    refs = mysql(cfg, statements)
    redis = RedisReadOnly(cfg)
    try:
        sessions, index = redis.sessions()
    finally:
        redis.close()
    marker = "/" + cfg["bucket"] + "/"
    known_roots = {key.split("/")[0] for key in objects}
    def object_key(raw):
        raw = raw.strip()
        if marker in raw:
            raw = raw.split(marker, 1)[1]
        elif "://" in raw:
            return None
        else:
            raw = raw.lstrip("/")
            if raw.startswith(cfg["bucket"] + "/"):
                raw = raw[len(cfg["bucket"]) + 1:]
            if raw.split("/")[0] not in known_roots:
                return None
        # Match literal legacy URLs before interpreting query/fragment delimiters.
        if raw in objects: return raw
        path = raw.split("?", 1)[0].split("#", 1)[0]
        if path in objects: return path
        return url.unquote(path)
    by_key, external = collections.defaultdict(list), []
    for ref in refs:
        key = object_key(ref["value"])
        if key is None:
            external.append({k: v for k, v in ref.items() if k != "value"})
        else:
            by_key[key].append({k: v for k, v in ref.items() if k != "value"})
    # Runtime files only; migration SQL, docs and build output are historical evidence.
    sources = []
    for base in (SERVER / "src/main", ROOT / "vibe-music-client/src", ROOT / "vibe-music-client/public", ROOT / "vibe-music-admin/src", ROOT / "vibe-music-admin/public"):
        for file in base.rglob("*"):
            if file.is_file() and file.suffix.lower() in {".java", ".xml", ".vue", ".ts", ".js", ".json", ".html", ".css", ".yml"}:
                text = file.read_text(encoding="utf-8", errors="replace")
                sources.append((file.relative_to(ROOT).as_posix(), text))
    protected_prefixes = ["staging/" + s["type"] + "/" + s["upload_id"] + "/" for s in sessions]
    candidates, protected, static_only = [], [], []
    for key, obj in objects.items():
        if key in by_key: continue
        source_refs = [name for name, content in sources if key in content or url.quote(key, safe="/") in content]
        if source_refs:
            static_only.append(dict(obj, source_files=source_refs))
        elif any(key.startswith(p) for p in protected_prefixes):
            protected.append(obj)
        elif any(key in s["payload"] for s in sessions):
            protected.append(obj)
        else:
            reason = "no_current_database_runtime_or_redis_reference"
            if key.startswith("staging/album-upload/"):
                reason = "no_redis_session_but_in_memory_album_session_cannot_be_excluded"
            elif key.startswith("staging/song-upload/"):
                upload_id = key.split("/")[2]
                reason = "staging_no_redis_session_indexed" if upload_id in index else "staging_no_redis_session_or_cleanup_index"
            candidates.append(dict(obj, reason=reason))
    missing = [{"key": key, "references": rows} for key, rows in by_key.items() if key not in objects]
    shared = [{"key": key, "references": rows, "exists": key in objects} for key, rows in by_key.items() if len(rows) > 1]
    groups = {}
    for obj in candidates:
        folder = obj["key"].rsplit("/", 1)[0]
        group = groups.setdefault(folder, {"count": 0, "bytes": 0})
        group["count"] += 1
        group["bytes"] += obj["bytes"]
    report = {
        "started_at": started, "finished_at": dt.datetime.now(TZ).isoformat(),
        "endpoint": cfg["endpoint"], "bucket": cfg["bucket"], "database": cfg["database"],
        "versioning": versioning, "cleanup_table_present": cleanup_present,
        "summary": {"object_count": len(objects), "total_bytes": sum(o["bytes"] for o in objects.values()),
                    "database_media_rows": len(refs), "referenced_existing_objects": len(set(by_key) & set(objects)),
                    "candidate_count": len(candidates), "candidate_bytes": sum(o["bytes"] for o in candidates),
                    "missing_object_count": len(missing), "shared_object_count": len(shared),
                    "redis_upload_session_count": len(sessions), "redis_cleanup_index_count": len(index),
                    "protected_staging_count": len(protected), "runtime_static_only_count": len(static_only)},
        "candidate_groups": groups, "candidates": candidates, "missing": missing, "shared": shared,
        "static_only": static_only, "protected_staging": protected,
        "redis_sessions": [{k: v for k, v in s.items() if k != "payload"} for s in sessions],
        "unmapped_external_references": external,
        "objects": list(objects.values()),
        "limits": ["No data or objects were deleted or updated.",
                   "Unreferenced does not prove a historical deletion; no deletion audit log was available.",
                   "MySQL, Redis and MinIO snapshots are not globally atomic; recheck before any manual deletion.",
                   "In-process album upload sessions cannot be inspected by this read-only script.",
                   "Disabled artists, private playlists and draft albums are included in database references.",
                   "Historical chat JSON, migrations and documentation are not treated as active ownership.",
                   "Object versions and incomplete multipart uploads are outside this inventory."]}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "minio-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("started_at", "finished_at", "versioning", "cleanup_table_present", "summary", "candidate_groups")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
