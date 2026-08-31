#!/usr/bin/env python3

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "sdk-device"


class SdkDeviceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        directory = Path(self.temp.name)
        self.log = directory / "adb.jsonl"
        self.adb = directory / "adb"
        self.adb.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, sys\n"
            "with open(os.environ['ADB_LOG'], 'a', encoding='utf-8') as f:\n"
            "    f.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "if sys.argv[-1:] == ['get-state']:\n"
            "    print('device')\n"
            "elif 'instrument' in sys.argv:\n"
            "    if os.environ.get('ADB_INSTRUMENT_FAILURE'):\n"
            "        print('INSTRUMENTATION_STATUS_CODE: -2')\n"
            "    print('INSTRUMENTATION_CODE: ' + os.environ.get('ADB_INSTRUMENT_CODE', '-1'))\n",
            encoding="utf-8",
        )
        self.adb.chmod(0o755)
        self.env = {
            **os.environ,
            "ADB": str(self.adb),
            "ADB_LOG": str(self.log),
            "ANDROID_SERIAL": "test-device",
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_tool(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(TOOL), *arguments],
            env=self.env,
            text=True,
            capture_output=True,
            check=False,
        )

    def calls(self) -> list[list[str]]:
        return [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()]

    def test_install_uses_selected_serial_and_bounded_flags(self) -> None:
        apk = Path(self.temp.name) / "test.apk"
        apk.touch()
        result = self.run_tool("install", "--apk", str(apk))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(["-s", "test-device", "install", "-r", "-t", str(apk)], self.calls())

    def test_instrument_requires_successful_terminal_code(self) -> None:
        result = self.run_tool(
            "instrument",
            "--package", "at.example.test",
            "--runner", "androidx.test.runner.AndroidJUnitRunner",
            "--class", "at.example.ExampleTest#passes",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("INSTRUMENTATION_CODE: -1", result.stdout)

    def test_instrument_quotes_nested_class_for_remote_shell(self) -> None:
        result = self.run_tool(
            "instrument",
            "--package", "at.example.test",
            "--runner", "androidx.test.runner.AndroidJUnitRunner",
            "--class", "at.example.ExampleTest$Nested#passes",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        instrument_call = next(call for call in self.calls() if "instrument" in call)
        self.assertIn("'at.example.ExampleTest$Nested#passes'", instrument_call)

    def test_instrument_rejects_non_success_terminal_code(self) -> None:
        self.env["ADB_INSTRUMENT_CODE"] = "-10"
        result = self.run_tool(
            "instrument",
            "--package", "at.example.test",
            "--runner", "androidx.test.runner.AndroidJUnitRunner",
            "--class", "at.example.ExampleTest#fails",
        )
        self.assertEqual(2, result.returncode)

    def test_instrument_rejects_failed_test_status(self) -> None:
        self.env["ADB_INSTRUMENT_FAILURE"] = "1"
        result = self.run_tool(
            "instrument",
            "--package", "at.example.test",
            "--runner", "androidx.test.runner.AndroidJUnitRunner",
            "--class", "at.example.ExampleTest#fails",
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("failed test", result.stderr)

    def test_run_as_preserves_argument_boundaries(self) -> None:
        result = self.run_tool("run-as", "--package", "at.example.test", "--", "test", "-f", "files/state")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            ["-s", "test-device", "shell", "run-as", "at.example.test", "test", "-f", "files/state"],
            self.calls(),
        )

    def test_cleanup_rejects_paths_outside_private_files(self) -> None:
        result = self.run_tool("cleanup", "--package", "at.example.test", "--file", "../secret")
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.log.exists())

    def test_cleanup_rejects_remote_shell_syntax(self) -> None:
        result = self.run_tool(
            "cleanup", "--package", "at.example.test", "--file", "files/state;rm"
        )
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
