# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock


def _probe(path):
    try:
        done = subprocess.run([path, '-c', 'printf ok'],
                              capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        # A candidate that hangs or cannot be executed is only that one
        # candidate's failure; the search has to continue past it.
        return False
    return done.returncode == 0 and done.stdout.strip() == 'ok'


def _resolve_bash():
    """Absolute path to a POSIX bash, or a hard error naming what was searched.

    A missing interpreter must fail the suite rather than skip it: a skip
    would report a green run that never exercised the host runner at all.
    """
    explicit = os.environ.get('PHANTOM_BASH')
    if explicit:
        # Made absolute here: the runner is launched from a temporary
        # directory, where a relative interpreter no longer resolves.
        located = explicit if os.path.dirname(explicit) else (
            shutil.which(explicit) or explicit)
        located = os.path.abspath(located)
        if _probe(located):
            return located
        raise RuntimeError('PHANTOM_BASH=%r is not a working POSIX bash' % explicit)
    # On Windows a bare 'bash' is resolved by CreateProcess from
    # %SystemRoot%/System32 before any PATH entry, and that bash.exe is the WSL
    # launcher, which cannot run this repository's Windows paths. Neither PATH
    # nor the current directory changes that order, so it is never a candidate.
    system32 = os.path.normcase(os.path.join(
        os.environ.get('SystemRoot', 'C:/Windows'), 'System32'))
    searched = []
    for candidate in (shutil.which('bash'),
                      'C:/Program Files/Git/usr/bin/bash.exe',
                      'C:/Program Files/Git/bin/bash.exe',
                      '/bin/bash', '/usr/bin/bash'):
        if not candidate or candidate in searched:
            continue
        searched.append(candidate)
        if os.path.normcase(os.path.dirname(os.path.abspath(candidate))) == system32:
            continue
        if _probe(candidate):
            return os.path.abspath(candidate)
    raise RuntimeError('no POSIX bash found; set PHANTOM_BASH to one. Searched: %s'
                       % ', '.join(searched))


def _restore_env(name, value):
    if value is None:
        os.environ.pop(name, None)
    else:
        os.environ[name] = value


_RESOLVED = []


def bash_executable():
    if not _RESOLVED:
        _RESOLVED.append(_resolve_bash())
    return _RESOLVED[0]


class AndroidHostRunnerTest(unittest.TestCase):
    def run_runner(self, regular=0, paparazzi=0, args=()):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'scripts').mkdir()
            shutil.copyfile(Path(__file__).resolve().parents[1] / 'test-android-host.sh',
                            root / 'scripts/test-android-host.sh')
            gradle = root / 'gradlew'
            gradle.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> calls.txt
for arg in "$@"; do
    case "$arg" in
        -PphantomHostTestEngine=regular) exit "$REGULAR_RC" ;;
        -PphantomHostTestEngine=paparazzi) exit "$PAPARAZZI_RC" ;;
    esac
done
exit 99
''')
            gradle.chmod(0o755)
            bash = bash_executable()
            # The runner's own `#!/usr/bin/env bash` is resolved through PATH,
            # so the chosen interpreter has to lead it; otherwise the child
            # process picks a different bash than the one probed here.
            env = dict(os.environ, REGULAR_RC=str(regular), PAPARAZZI_RC=str(paparazzi))
            env['PATH'] = os.path.dirname(bash) + os.pathsep + env.get('PATH', '')
            result = subprocess.run([bash, str(root / 'scripts/test-android-host.sh'), *args],
                cwd=temp, env=env, capture_output=True, text=True, timeout=10)
            calls = root / 'calls.txt'
            return result, calls.read_text().splitlines() if calls.exists() else []

    def test_runs_both_complementary_engines_without_cache(self):
        result, calls = self.run_runner(args=['--offline'])
        self.assertEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        for call, engine in zip(calls, ['regular', 'paparazzi']):
            self.assertIn(f'-PphantomHostTestEngine={engine}', call)
            for flag in ['--rerun-tasks', '--no-build-cache', '--no-daemon', '--offline']:
                self.assertIn(flag, call)
            self.assertNotIn('recordPaparazzi', call)
            self.assertNotIn('verifyPaparazzi', call)

    def test_second_success_cannot_hide_first_failure(self):
        result, calls = self.run_runner(regular=1)
        self.assertEqual(1, result.returncode)
        self.assertEqual(2, len(calls))

    def test_second_failure_is_not_ignored(self):
        result, calls = self.run_runner(paparazzi=1)
        self.assertEqual(1, result.returncode)
        self.assertEqual(2, len(calls))

    def test_explicit_relative_interpreter_survives_the_change_of_directory(self):
        directory, name = os.path.split(bash_executable())
        previous_cwd = os.getcwd()
        previous = os.environ.get('PHANTOM_BASH')
        os.chdir(directory)
        os.environ['PHANTOM_BASH'] = os.path.join(os.curdir, name)
        try:
            resolved = _resolve_bash()
        finally:
            os.chdir(previous_cwd)
            _restore_env('PHANTOM_BASH', previous)
        self.assertTrue(os.path.isabs(resolved), resolved)
        elsewhere = subprocess.run([resolved, '-c', 'printf ok'],
            cwd=tempfile.gettempdir(), capture_output=True, text=True, timeout=10)
        self.assertEqual('ok', elsewhere.stdout.strip())

    def test_a_hanging_candidate_does_not_end_the_search(self):
        hanging = os.path.join(os.path.dirname(bash_executable()), 'hanging-bash')
        genuine = subprocess.run

        def fake_run(command, **kwargs):
            if command[0] == hanging:
                raise subprocess.TimeoutExpired(command, kwargs.get('timeout'))
            return genuine(command, **kwargs)

        previous = os.environ.pop('PHANTOM_BASH', None)
        try:
            with mock.patch.object(subprocess, 'run', fake_run):
                with mock.patch.object(shutil, 'which', lambda name: hanging):
                    self.assertFalse(_probe(hanging))
                    resolved = _resolve_bash()
        finally:
            _restore_env('PHANTOM_BASH', previous)
        self.assertNotEqual(hanging, resolved)
        self.assertTrue(_probe(resolved))

    def test_filter_cannot_silently_turn_full_suite_into_focused_run(self):
        result, calls = self.run_runner(args=['--tests', '*OneTest'])
        self.assertEqual(2, result.returncode)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
