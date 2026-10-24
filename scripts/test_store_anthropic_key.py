"""The local key setup writes only a private file and never prints its value."""
import importlib.util
import io
import pathlib
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = pathlib.Path(__file__).with_name("store-anthropic-key.py")


def load_script():
    spec = importlib.util.spec_from_file_location("store_anthropic_key", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StoreAnthropicKeyTest(unittest.TestCase):
    def test_private_file_no_overwrite_and_no_output_leak(self):
        key = "TEST_ONLY_SECRET_VALUE"
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / "models"
            module = load_script()
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(module.getpass, "getpass", return_value=key), \
                    patch("sys.stdout", stdout), patch("sys.stderr", stderr):
                module.main(["--directory", str(directory)])
            file = directory / "anthropic_api_key"
            self.assertEqual(key, file.read_text())
            self.assertEqual(0o700, directory.stat().st_mode & 0o777)
            self.assertEqual(0o600, file.stat().st_mode & 0o777)
            self.assertNotIn(key, stdout.getvalue() + stderr.getvalue())
            with patch.object(module.getpass, "getpass", return_value="REPLACEMENT"):
                with self.assertRaises(ValueError):
                    module.main(["--directory", str(directory)])
            self.assertEqual(key, file.read_text())

    def test_refuses_symlinked_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            destination = root / "destination"
            destination.mkdir()
            link = root / "models"
            link.symlink_to(destination, target_is_directory=True)
            module = load_script()
            with patch.object(module.getpass, "getpass", return_value="TEST_ONLY_SECRET_VALUE"):
                with self.assertRaises(ValueError):
                    module.main(["--directory", str(link)])
            self.assertEqual([], list(destination.iterdir()))
