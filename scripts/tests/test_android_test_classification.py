# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Willen LLC
"""Negative controls for the Android CI test-classification guard.

The guard lives inline in `.github/workflows/android.yml` (job
`android-test-classification-guard`). These tests extract that exact
program from the workflow, run it against a temporary copy of the
workflow plus a synthetic `androidUnitTest` tree, and prove that each
inconsistency it claims to catch is actually caught. A guard that only
checked the manifest would let a declared-but-never-run class pass;
control 2 below is the one that pins the execution requirement.
Discovery follows runnable declarations (a top-level class whose body
carries @Test), not file or class names: controls 6, 7, 9 and 10 pin the
shapes a name-based scan got wrong.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / '.github' / 'workflows' / 'android.yml'
STACKTRACE = '            --stacktrace\n'


def _guard_program(workflow_text):
    """The python program the guard step feeds to `python3 - <<'PY'`."""
    m = re.search(r"python3 - <<'PY'\n(.*?)\n\s*PY\n", workflow_text, re.S)
    if not m:
        raise AssertionError('guard program not found in android.yml')
    lines = m.group(1).splitlines()
    indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return '\n'.join(l[indent:] for l in lines) + '\n'


def _run_lists(workflow_text):
    def section(start, end):
        i = workflow_text.index(start)
        return workflow_text[i:workflow_text.index(end, i)]
    jvm = re.findall(r'--tests "([^"]+)"', section('\n  android-jvm-state:', '\n  android-compose-ui:'))
    compose = re.findall(r'^\s*- (phantom\.[\w.]+)$', section('\n  android-compose-ui:', '\n  android-paparazzi:'), re.M)
    pap = re.findall(r'--tests "([^"]+)"', section('\n  android-paparazzi:', '\n  android-test-classification-guard:'))
    return jvm, compose, pap


def _runnable(pkg, *names):
    """Kotlin source declaring one runnable JUnit class per name."""
    body = ''.join('\nclass %s {\n    @Test\n    fun runs() {\n    }\n}\n' % n for n in names)
    return 'package %s\n\nimport org.junit.Test\n%s' % (pkg, body)


MANIFEST_OPEN = '          MANIFEST = """\n'


def _manifest(workflow_text):
    m = re.search(r'MANIFEST = """\n(.*?)\n\s*"""', workflow_text, re.S)
    return [l.strip() for l in m.group(1).splitlines() if l.strip()]


def _with_jvm_filter(workflow_text, fqn):
    """Add one --tests filter to the jvm-state step."""
    i = workflow_text.index('\n  android-jvm-state:')
    j = workflow_text.index(STACKTRACE, i)
    return workflow_text[:j] + '            --tests "%s" \\\n' % fqn + workflow_text[j:]


def _with_manifest_entry(workflow_text, fqn):
    assert workflow_text.count(MANIFEST_OPEN) == 1
    return workflow_text.replace(MANIFEST_OPEN, MANIFEST_OPEN + '          %s\n' % fqn, 1)


class AndroidTestClassificationGuardTest(unittest.TestCase):
    """Each case builds a temp repo: workflow copy + synthetic test tree."""

    def setUp(self):
        self.text = WORKFLOW.read_text(encoding='utf-8')
        self.tmp = Path(tempfile.mkdtemp(prefix='phantom-guard-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _write_tree(self, classes, extra_files=()):
        """One `<Name>.kt` per class, declaring `package` + a runnable class.

        `extra_files` are (relative path, source text) pairs for the shapes
        the guard must see through: a file whose declared class is not the
        file name, a file declaring a second test class, a class whose name
        ends in a number, a helper without tests.
        """
        root = self.tmp / 'apps' / 'android' / 'src' / 'androidUnitTest' / 'kotlin'
        for fqn in classes:
            pkg, name = fqn.rsplit('.', 1)
            p = root.joinpath(*fqn.split('.')).with_suffix('.kt')
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(_runnable(pkg, name), encoding='utf-8')
        for rel, text in extra_files:
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding='utf-8')
        # A helper without @Test is not a unit, as in the repo.
        helper = root / 'phantom' / 'android' / 'OnboardingV2SemanticsMatchers.kt'
        helper.parent.mkdir(parents=True, exist_ok=True)
        helper.write_text('package phantom.android\n\nobject OnboardingV2SemanticsMatchers\n', encoding='utf-8')
        return root

    def _run(self, workflow_text, tree_classes, extra_files=()):
        root = self._write_tree(tree_classes, extra_files)
        wf = self.tmp / 'android.yml'
        wf.write_text(workflow_text, encoding='utf-8')
        env = dict(os.environ, PHANTOM_ANDROID_WORKFLOW=str(wf), PHANTOM_ANDROID_UNIT_TEST_ROOT=str(root))
        done = subprocess.run([sys.executable, '-B', '-'], input=_guard_program(workflow_text),
                              capture_output=True, text=True, encoding='utf-8', errors='replace',
                              env=env, cwd=self.tmp, timeout=60)
        return done.returncode, done.stdout + done.stderr

    def _consistent(self):
        """A workflow whose manifest equals its run lists, and a matching tree."""
        jvm, compose, pap = _run_lists(self.text)
        manifest = _manifest(self.text)
        self.assertEqual(sorted(manifest), sorted(set(jvm) | set(compose) | set(pap)),
                         'the committed workflow itself must be consistent')
        return manifest

    def test_committed_workflow_is_consistent_against_its_own_manifest(self):
        manifest = self._consistent()
        rc, out = self._run(self.text, manifest)
        self.assertEqual(0, rc, out)
        self.assertIn('manifest == executed', out)

    def test_control_1_a_source_class_missing_from_the_manifest_is_reported(self):
        manifest = self._consistent()
        extra = 'phantom.android.zz.UnlistedNewTest'
        rc, out = self._run(self.text, manifest + [extra])
        self.assertEqual(1, rc, out)
        self.assertIn('unclassified', out)
        self.assertIn(extra, out)

    def test_control_2_a_manifest_class_absent_from_every_run_list_is_reported(self):
        # Declaration must not replace execution: keep the class in the
        # manifest and on disk, remove it from the shard that runs it.
        manifest = self._consistent()
        jvm, _, _ = _run_lists(self.text)
        victim = sorted(jvm)[0]
        line = '            --tests "%s" \\\n' % victim
        self.assertIn(line, self.text)
        edited = self.text.replace(line, '', 1)
        rc, out = self._run(edited, manifest)
        self.assertEqual(1, rc, out)
        self.assertIn('declared but never executed', out)
        self.assertIn(victim, out)

    def test_control_3_a_run_list_class_absent_from_the_manifest_is_reported(self):
        manifest = self._consistent()
        extra = 'phantom.android.zz.RunButUndeclaredTest'
        rc, out = self._run(_with_jvm_filter(self.text, extra), manifest + [extra])
        self.assertEqual(1, rc, out)
        self.assertIn('executed but never declared', out)
        self.assertIn(extra, out)

    def test_control_4_a_stale_manifest_entry_without_a_source_file_is_reported(self):
        manifest = self._consistent()
        stale = manifest[0]
        rc, out = self._run(self.text, [c for c in manifest if c != stale])
        self.assertEqual(1, rc, out)
        self.assertIn('stale', out)
        self.assertIn(stale, out)

    def test_control_5_a_class_in_two_shards_is_reported(self):
        manifest = self._consistent()
        _, compose, _ = _run_lists(self.text)
        dup = compose[0]
        rc, out = self._run(_with_jvm_filter(self.text, dup), manifest)
        self.assertEqual(1, rc, out)
        self.assertIn('more than one shard', out)
        self.assertIn(dup, out)

    def test_control_6_a_second_test_class_declared_in_a_listed_file_is_reported(self):
        # A `--tests` filter selects a class. A file that declares two test
        # classes hides the second one from a file-name based guard, and CI
        # then never runs it. The guard must see the declared class.
        manifest = self._consistent()
        host = manifest[0]
        pkg, name = host.rsplit('.', 1)
        hidden = name + 'HiddenSibling'
        rel = '/'.join(host.split('.')) + '.kt'
        rc, out = self._run(self.text, [c for c in manifest if c != host],
                            extra_files=[(rel, _runnable(pkg, name, hidden))])
        self.assertEqual(1, rc, out)
        self.assertIn('unclassified', out)
        self.assertIn(pkg + '.' + hidden, out)

    def test_control_7_a_class_named_differently_from_its_file_is_reported(self):
        # The run list and the manifest name the FILE; the file declares a
        # class of another name, so Gradle's filter matches nothing and the
        # class never runs. The guard must report both halves: the declared
        # class is unclassified and the file-name entry is stale.
        manifest = self._consistent()
        jvm, _, _ = _run_lists(self.text)
        victim = sorted(jvm)[0]
        pkg, name = victim.rsplit('.', 1)
        declared = name + 'Renamed'
        rel = '/'.join(victim.split('.')) + '.kt'
        rc, out = self._run(self.text, [c for c in manifest if c != victim],
                            extra_files=[(rel, _runnable(pkg, declared))])
        self.assertEqual(1, rc, out)
        self.assertIn('stale', out)
        self.assertIn(victim, out)
        self.assertIn('unclassified', out)
        self.assertIn(pkg + '.' + declared, out)

    def test_control_8_a_test_named_file_declaring_no_runnable_class_is_reported(self):
        manifest = self._consistent()
        rc, out = self._run(self.text, manifest, extra_files=[
            ('phantom/android/zz/EmptyTest.kt', 'package phantom.android.zz\n\nclass EmptyTest {\n}\n')])
        self.assertEqual(1, rc, out)
        self.assertIn('declare no runnable class', out)
        self.assertIn('phantom/android/zz/EmptyTest.kt', out)

    def test_control_9_a_runnable_class_whose_name_ends_in_a_number_is_discovered(self):
        # HybridRelayTransportIntegrationTest20 is such a class: file and
        # class both end in `Test20`. A `*Test` filter on names treated it
        # as absent and dropped a real test from CI. Listed in the shard
        # and the manifest, it must be accepted as executed.
        manifest = self._consistent()
        t20 = 'phantom.android.zz.SomethingTest20'
        edited = _with_manifest_entry(_with_jvm_filter(self.text, t20), t20)
        rc, out = self._run(edited, manifest + [t20])
        self.assertEqual(0, rc, out)
        self.assertIn('manifest == executed', out)

    def test_control_10_omitting_a_runnable_test20_class_is_reported(self):
        # The same class present on disk but in neither list: unclassified,
        # not silently absent.
        manifest = self._consistent()
        t20 = 'phantom.android.zz.SomethingTest20'
        rc, out = self._run(self.text, manifest + [t20])
        self.assertEqual(1, rc, out)
        self.assertIn('unclassified', out)
        self.assertIn(t20, out)

    def test_control_11_a_helper_without_tests_is_not_a_unit_whatever_its_name(self):
        manifest = self._consistent()
        rc, out = self._run(self.text, manifest, extra_files=[
            ('phantom/android/zz/FakeRelayHelper.kt', 'package phantom.android.zz\n\nclass FakeRelayHelper {\n    fun ok() = true\n}\n')])
        self.assertEqual(0, rc, out)


if __name__ == '__main__':
    unittest.main()
