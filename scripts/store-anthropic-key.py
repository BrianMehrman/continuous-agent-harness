#!/usr/bin/env python3
"""Store one Anthropic API key in a private local file."""

import argparse
import getpass
import os
import re
import sys
from pathlib import Path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="anthropic_api_key")
    parser.add_argument("--directory", type=Path, default=Path(".secrets/models"))
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[a-z0-9_]{1,64}", args.name):
        raise ValueError("Invalid credential reference")
    directory = args.directory
    if directory.is_symlink():
        raise ValueError("Credential directory must not be a symlink")
    old_umask = os.umask(0o077)
    try:
        directory.mkdir(parents=True, mode=0o700, exist_ok=True)
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError("Credential directory must not be a symlink")
        directory.chmod(0o700)
        path = directory / args.name
        if path.exists() or path.is_symlink():
            raise ValueError("Credential file already exists")
        key = getpass.getpass("Anthropic API key: ").strip()
        if not key:
            raise ValueError("Anthropic API key cannot be empty")
        with path.open("x", encoding="utf-8") as stream:
            stream.write(key)
        path.chmod(0o600)
        print(f"Stored key reference {args.name}")
        return 0
    finally:
        os.umask(old_umask)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
