#!/usr/bin/env python3
"""Exercise Temporal's current Worker Deployment API forward and rollback path."""
import argparse
import os
import subprocess
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--forward-and-rollback", action="store_true")
    args = parser.parse_args()
    if not args.forward_and_rollback:
        parser.error("choose --forward-and-rollback")
    subprocess.run(["./gradlew", "integrationTest", "--tests", "*DeploymentIT", "--offline"],
                   check=True, env=os.environ.copy())
    return 0


if __name__ == "__main__":
    sys.exit(main())
