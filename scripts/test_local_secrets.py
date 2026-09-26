"""Run with python3 -m unittest discover -s scripts -p 'test_*.py'."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "scripts/create-local-secrets.py"


class LocalSecretsTest(unittest.TestCase):
    def test_private_directories_container_readable_files_and_no_overwrite(self):
        (ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "target") as temporary:
            root = Path(temporary) / "secrets"
            command = [sys.executable, str(GENERATOR), "--directory", str(root)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            files = [p for p in root.rglob("*") if p.is_file()]
            self.assertEqual(len(files), 4)
            contents = {p: p.read_text() for p in files}
            self.assertEqual(len(set(contents.values())), 4)
            for path, secret in contents.items():
                self.assertEqual(len(secret), 64)
                self.assertNotIn(secret, first.stdout + first.stderr)
                self.assertEqual(path.stat().st_mode & 0o777, 0o444)
            for directory in [root, root / "app"]:
                self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(contents, {p: p.read_text() for p in files})

    def test_refuses_symlinked_directory(self):
        (ROOT / "target").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "target") as temporary:
            root = Path(temporary)
            destination = root / "destination"
            destination.mkdir()
            link = root / "secrets"
            link.symlink_to(destination, target_is_directory=True)
            result = subprocess.run([sys.executable, str(GENERATOR), "--directory", str(link)],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list(destination.iterdir()), [])
