"""Run on the broker host; never in the APK. No public administrator endpoint."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
from .security import State,b64,unb64
import secrets


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["new-master-key","pair-code","devices","revoke","rotate-master-key"])
    parser.add_argument("--device")
    args=parser.parse_args()
    if args.action=="new-master-key":
        print(b64(secrets.token_bytes(32)))
        return
    state=State(Path(os.environ.get("BROKER_STATE_PATH","/data/broker.sqlite")),unb64(os.environ["BROKER_MASTER_KEY"]))
    if args.action=="pair-code":
        print(state.pairing_code())
    elif args.action=="devices":
        with state.connect() as db:
            for row in db.execute("SELECT id,revoked,created FROM devices ORDER BY created"):
                print(dict(row))
    elif args.action=="revoke":
        if not args.device:parser.error("--device is required")
        with state.connect() as db:db.execute("UPDATE devices SET revoked=1 WHERE id=?",(args.device,))
    else:
        # Maintenance-only rotation. Stop the API process and snapshot the broker DB
        # before changing the master key; no inventory/photographs are stored here.
        replacement=unb64(os.environ["BROKER_NEW_MASTER_KEY"])
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        new_cipher=AESGCM(replacement)
        with state.connect() as db:
            db.execute("BEGIN EXCLUSIVE")
            credentials=[(r["name"],state.decrypt(r["name"],r["envelope"])) for r in db.execute("SELECT * FROM credentials")]
            operations=[(r["id"],state.decrypt("operation:"+r["id"],r["result"])) for r in db.execute("SELECT * FROM operations WHERE result!=''")]
            state.cipher=new_cipher
            for name,value in credentials:db.execute("UPDATE credentials SET envelope=? WHERE name=?",(state.encrypt(name,value),name))
            for identity,value in operations:db.execute("UPDATE operations SET result=? WHERE id=?",(state.encrypt("operation:"+identity,value),identity))
        print("Rotated encrypted broker records. Set BROKER_MASTER_KEY to the new key before restarting.")


if __name__=="__main__":main()
