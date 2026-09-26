#!/usr/bin/env python3
"""Create local development credentials without printing or overwriting them."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--directory", type=Path, default=Path(".secrets"))
args = parser.parse_args()
os.umask(0o077)
root = args.directory
paths = [root / "postgres_admin_password", root / "temporal_db_password",
         root / "temporal_visibility_password", root / "app/spring.datasource.password"]
if any(path.exists() or path.is_symlink() for path in paths):
    parser.error("Credential files already exist; refusing to overwrite. Follow the rotation guide.")
for directory in [root, root / "app"]:
    if directory.is_symlink():
        parser.error("Secret directories must not be symbolic links")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
for path in paths:
    with path.open("x") as stream:
        stream.write(secrets.token_hex(32))
    # Compose bind mounts retain host UIDs. Private parent directories protect
    # host access while the read-only mount is readable by container UIDs.
    path.chmod(0o444)
print("Created four credential files. Values were not printed. Existing database passwords are unchanged.")
