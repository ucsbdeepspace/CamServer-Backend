import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import zipfile


class RemoveLegacyUiTest(unittest.TestCase):
    def test_preserves_nested_libraries_and_source_after_removal(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "backend.jar"
            output = Path(temp) / "api-only.jar"
            with zipfile.ZipFile(source, "w") as jar:
                jar.writestr("BOOT-INF/classes/templates/index.html", "retired")
                jar.writestr("BOOT-INF/classes/static/old.png", b"old-image")
                jar.writestr("BOOT-INF/classes/edu/camserver/app/controller/PageController.class", b"old-controller")
                jar.writestr("BOOT-INF/lib/nested.jar", b"PK\x03\x04nested-library", compress_type=zipfile.ZIP_STORED)
                jar.writestr("BOOT-INF/classes/Api.class", b"existing-api", compress_type=zipfile.ZIP_DEFLATED)
            original = source.read_bytes()
            subprocess.run([sys.executable, str(Path(__file__).with_name("remove_legacy_ui.py")), str(source), str(output)], check=True, capture_output=True)
            self.assertEqual(source.read_bytes(), original)
            with zipfile.ZipFile(output) as jar:
                self.assertEqual(set(jar.namelist()), {"BOOT-INF/lib/nested.jar", "BOOT-INF/classes/Api.class"})
                self.assertEqual(jar.read("BOOT-INF/classes/Api.class"), b"existing-api")
                self.assertEqual(jar.read("BOOT-INF/lib/nested.jar"), b"PK\x03\x04nested-library")
                self.assertEqual(jar.getinfo("BOOT-INF/lib/nested.jar").compress_type, zipfile.ZIP_STORED)

    def test_refuses_to_overwrite_existing_output(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "keep.jar"
            path.write_bytes(b"keep")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("remove_legacy_ui.py")), str(path), str(path)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(path.read_bytes(), b"keep")


if __name__ == "__main__":
    unittest.main()
