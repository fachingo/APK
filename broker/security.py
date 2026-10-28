"""Device pairing and replay-resistant requests; no inventory or photo persistence."""
from __future__ import annotations
import base64
import hashlib
import hmac
import json
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def unb64(value: str) -> bytes:
    if len(value) > 3_000_000:
        raise ValueError("Oversized credential")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()


class BrokerError(Exception):
    def __init__(self, code: str, message: str, status: int = 400, retry_after: int = 0):
        super().__init__(message)
        self.code, self.message, self.status, self.retry_after = code, message, status, retry_after


class State:
    def __init__(self, path: Path, master_key: bytes):
        if len(master_key) != 32:
            raise ValueError("BROKER_MASTER_KEY must decode to 32 random bytes")
        self.path, self.cipher = path, AESGCM(master_key)
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,public_key TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS pairing(code_hash TEXT PRIMARY KEY,expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS nonces(device TEXT NOT NULL,nonce TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(device,nonce));
                CREATE TABLE IF NOT EXISTS rate_limits(subject TEXT NOT NULL,bucket INTEGER NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(subject,bucket));
                CREATE TABLE IF NOT EXISTS credentials(name TEXT PRIMARY KEY,envelope TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS oauth(state_hash TEXT PRIMARY KEY,device TEXT NOT NULL,expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,kind TEXT NOT NULL,request_hash TEXT NOT NULL,state TEXT NOT NULL,result TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS resources(id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,kind TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,kind TEXT NOT NULL,outcome TEXT NOT NULL,request_hash TEXT NOT NULL,created INTEGER NOT NULL);
            """)
            columns={row[1] for row in db.execute("PRAGMA table_info(operations)")}
            if "context" not in columns:
                db.execute("ALTER TABLE operations ADD COLUMN context TEXT NOT NULL DEFAULT '{}' ")
        path.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA synchronous=FULL")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def encrypt(self, name: str, value: object) -> str:
        nonce = secrets.token_bytes(12)
        return b64(nonce + self.cipher.encrypt(nonce, canonical(value), name.encode()))

    def decrypt(self, name: str, envelope: str) -> object:
        value = unb64(envelope)
        return json.loads(self.cipher.decrypt(value[:12], value[12:], name.encode()))

    def get(self, name: str):
        with self.connect() as db:
            row = db.execute("SELECT envelope FROM credentials WHERE name=?", (name,)).fetchone()
        return self.decrypt(name, row[0]) if row else None

    def put(self, name: str, value: object):
        with self.connect() as db:
            db.execute("INSERT INTO credentials VALUES(?,?) ON CONFLICT(name) DO UPDATE SET envelope=excluded.envelope", (name, self.encrypt(name, value)))

    def rate(self, subject: str, limit: int):
        bucket = int(time.time()) // 60
        with self.connect() as db:
            db.execute("INSERT INTO rate_limits VALUES(?,?,1) ON CONFLICT(subject,bucket) DO UPDATE SET count=count+1", (subject, bucket))
            count = db.execute("SELECT count FROM rate_limits WHERE subject=? AND bucket=?", (subject,bucket)).fetchone()[0]
            db.execute("DELETE FROM rate_limits WHERE bucket<?", (bucket-2,))
        if count > limit:
            raise BrokerError("RATE_LIMIT", "Broker request limit reached; retry later.", 429, 60)

    def pairing_code(self, lifetime: int = 300) -> str:
        code = secrets.token_urlsafe(24)
        with self.connect() as db:
            db.execute("INSERT INTO pairing VALUES(?,?,0)", (digest(code.encode()),int(time.time())+lifetime))
        return code

    def pair(self, code: str, device: str, public_key: str):
        import uuid
        try:
            if str(uuid.UUID(device)) != device or len(public_key)>1000:raise ValueError()
        except (ValueError,TypeError,AttributeError):
            raise BrokerError("BAD_DEVICE","Invalid device identity.") from None
        try:
            key = serialization.load_der_public_key(unb64(public_key))
            if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
                raise ValueError()
        except (ValueError, TypeError):
            raise BrokerError("BAD_KEY", "A P-256 device public key is required.") from None
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM pairing WHERE code_hash=?", (digest(code.encode()),)).fetchone()
            if not row or row["used"] or row["expires"] < int(time.time()):
                raise BrokerError("PAIRING_EXPIRED", "Pairing code is invalid, expired or already used.", 401)
            previous = db.execute("SELECT * FROM devices WHERE id=?", (device,)).fetchone()
            if previous and (previous["public_key"] != public_key or previous["revoked"]):
                raise BrokerError("DEVICE_CONFLICT", "Use a new device identity after revocation or key replacement.", 409)
            db.execute("UPDATE pairing SET used=1 WHERE code_hash=?", (digest(code.encode()),))
            db.execute("INSERT OR IGNORE INTO devices VALUES(?,?,0,?)", (device,public_key,int(time.time())))

    def authenticate(self, audience: str, path: str, body: bytes, headers: dict[str,str]) -> str:
        try:
            device, nonce = headers["x-snapims-device"], headers["x-snapims-nonce"]
            timestamp = int(headers["x-snapims-time"])
            if abs(int(time.time())-timestamp) > 120 or len(nonce) not in range(32,129):
                raise ValueError()
            with self.connect() as db:
                row = db.execute("SELECT * FROM devices WHERE id=? AND revoked=0", (device,)).fetchone()
            if not row:
                raise ValueError()
            key = serialization.load_der_public_key(unb64(row["public_key"]))
            message = "\n".join(["SNAPIMS1", audience, "POST", path, str(timestamp), nonce, digest(body), device]).encode()
            key.verify(unb64(headers["x-snapims-signature"]), message, ec.ECDSA(hashes.SHA256()))
        except (KeyError, ValueError, TypeError, InvalidSignature):
            raise BrokerError("DEVICE_AUTH", "Device signature was rejected. Pair this installation again.", 401) from None
        self.rate("device:"+device,120)
        try:
            with self.connect() as db:
                db.execute("DELETE FROM nonces WHERE created<?", (int(time.time())-300,))
                db.execute("INSERT INTO nonces VALUES(?,?,?)", (device,nonce,int(time.time())))
        except sqlite3.IntegrityError:
            raise BrokerError("REPLAY", "This signed request was already used; send a fresh nonce.", 409) from None
        return device

    def audit(self, device: str, kind: str, outcome: str, request_hash: str):
        with self.connect() as db:
            db.execute("INSERT INTO audit(device,kind,outcome,request_hash,created) VALUES(?,?,?,?,?)", (device,kind,outcome,request_hash,int(time.time())))
