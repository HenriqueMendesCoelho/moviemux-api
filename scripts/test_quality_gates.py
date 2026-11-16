"""Negative tests for CI gates; all fixtures are synthetic and temporary."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        for name in ('check-test-reports.py', 'check-coverage.py'):
            shutil.copy(SCRIPTS / name, self.root / 'scripts' / name)

    def tearDown(self):
        self.temporary.cleanup()

    def execute(self, script):
        return subprocess.run(['python3', str(self.root / 'scripts' / script)], capture_output=True, text=True)

    def reports(self, tests=1, failures=0, skipped=0):
        for directory in ('surefire-reports', 'failsafe-reports'):
            path = self.root / 'target' / directory
            path.mkdir(parents=True, exist_ok=True)
            (path / 'TEST-synthetic.xml').write_text(
                f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}"/>')

    def test_missing_reports_fail(self):
        self.assertNotEqual(0, self.execute('check-test-reports.py').returncode)

    def test_zero_failed_or_skipped_tests_fail(self):
        for arguments in ({'tests': 0}, {'failures': 1}, {'skipped': 1}):
            with self.subTest(arguments):
                self.reports(**arguments)
                self.assertNotEqual(0, self.execute('check-test-reports.py').returncode)

    def test_successful_collected_tests_pass(self):
        self.reports()
        self.assertEqual(0, self.execute('check-test-reports.py').returncode)

    def test_coverage_below_measured_floor_fails(self):
        (self.root / 'scripts/coverage-ratchet.json').write_text(json.dumps({'user/usecase': {'LINE': 75}}))
        directory = self.root / 'target/site/jacoco'
        directory.mkdir(parents=True)
        for covered, expected in ((3, 0), (2, 1)):
            with self.subTest(covered=covered):
                (directory / 'jacoco.xml').write_text('<report><package name="com/moviemux/user/usecase">'
                    f'<counter type="LINE" covered="{covered}" missed="{4-covered}"/></package></report>')
                self.assertEqual(expected, self.execute('check-coverage.py').returncode)


if __name__ == '__main__':
    unittest.main()
