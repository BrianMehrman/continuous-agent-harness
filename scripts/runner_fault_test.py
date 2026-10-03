#!/usr/bin/env python3
"""Run actual JVM/Docker recovery tests against the configured local test database."""
import argparse, os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--all-boundaries',action='store_true',required=True);parser.parse_args()
env=os.environ.copy()
if not env.get('HARNESS_RUNNER_IMAGE'):
    env['HARNESS_RUNNER_IMAGE']=next(line.split('=',1)[1] for line in (root/'runner/image.lock').read_text().splitlines() if line.startswith('RUNNER_IMAGE='))
subprocess.run([str(root/'gradlew'),'integrationTest','--tests','*RunnerRecoveryIT','--rerun-tasks'],cwd=root,env=env,check=True)
