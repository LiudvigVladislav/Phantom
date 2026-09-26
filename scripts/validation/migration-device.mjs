import {execFileSync, spawn} from 'node:child_process';
import {writeFileSync, mkdirSync, readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {resolve, isAbsolute} from 'node:path';

const root = process.argv[2];
assert.ok(root && isAbsolute(root), 'Supply an absolute evidence directory for the disposable emulator');
const repo = resolve(fileURLToPath(new URL('../../', import.meta.url)));
const sdk = process.env.ANDROID_HOME;
assert.ok(sdk, 'Set ANDROID_HOME');
const adbPath = `${sdk}/platform-tools/adb`;
const serial = 'emulator-5580';
const pkg = 'phantom.android';
const runner = 'phantom.android.test/androidx.test.runner.AndroidJUnitRunner';
const testClass = 'phantom.android.screens.migration.MigrationDeviceTest';
const apk = `${repo}/apps/android/build/outputs/apk/debug/android-debug.apk`;
const testApk = `${repo}/apps/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk`;
const evidence = `${root}/run-${Date.now()}`;
mkdirSync(evidence, {recursive:true});
function adb(args) { return execFileSync(adbPath, ['-s', serial, ...args], {encoding:'utf8', timeout:120000, maxBuffer:5e6}); }
function shell(...args) { return adb(['shell', ...args]); }
function stop() { shell('am', 'force-stop', pkg); }
function testArgs(method, optIn=true) {
  return ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', `${testClass}#${method}`,
    '-e','isolatedMigration',optIn ? 'yes' : 'no', runner];
}
const testRuns = new Map();
function test(method) {
  stop();
  const out = adb(testArgs(method));
  const count = (testRuns.get(method) || 0) + 1;
  testRuns.set(method, count);
  writeFileSync(`${evidence}/${method}-${count}.txt`, out);
  assert.match(out, /OK \(1 test\)/);
  assert.doesNotMatch(out, /FAILURES|INSTRUMENTATION_FAILED|Process crashed/);
  console.log(`${method}: PASS`);
}
const delay = ms => new Promise(r => setTimeout(r, ms));
function uiNodes(name) {
  shell('uiautomator','dump','/sdcard/migration-ui.xml');
  const xml = shell('cat','/sdcard/migration-ui.xml');
  writeFileSync(`${evidence}/${name}.xml`,xml);
  return JSON.parse(execFileSync('python3',['-c',
    'import sys,json,xml.etree.ElementTree as E; print(json.dumps([n.attrib for n in E.fromstring(sys.stdin.read()).iter("node")]))'],
    {input:xml,encoding:'utf8'}));
}
async function waitUi(text, name) {
  for(let i=0;i<30;i++) {
    const nodes = uiNodes(name);
    const found = nodes.find(n=>n.text===text);
    if(found) {
      writeFileSync(`${evidence}/${name}.png`,execFileSync(adbPath,['-s',serial,'exec-out','screencap','-p'],{maxBuffer:1e7}));
      return found;
    }
    await delay(1000);
  }
  throw Error(`UI did not display ${text}`);
}
function tap(node) {
  assert.equal(node.enabled,'true');
  const [l,t,r,b] = node.bounds.match(/\d+/g).map(Number);
  shell('input','tap',String(Math.floor((l+r)/2)),String(Math.floor((t+b)/2)));
}
assert.match(adb(['emu', 'avd', 'name']), /^Phantom_Migration_20260926\r?\n/);
assert.equal(shell('getprop','sys.boot_completed').trim(), '1');
shell('svc','wifi','disable');
shell('svc','data','disable');
await delay(2000);
for (const path of [apk, testApk]) assert.match(adb(['install','-r','-t',path]), /Success/);
writeFileSync(`${evidence}/apk-sha256.json`, JSON.stringify(Object.fromEntries([apk,testApk].map(p=>[p,createHash('sha256').update(readFileSync(p)).digest('hex')])),null,2));
// Reset only this explicitly named disposable AVD's synthetic application fixture.
stop();
assert.match(shell('pm','clear',pkg), /Success/);
const refusal = adb(testArgs('seedLegacy', false));
writeFileSync(`${evidence}/opt-in-negative.txt`, refusal);
assert.match(refusal, /FAILURES/);
assert.match(refusal, /expected:<\[yes\]> but was:<\[no\]>/);
test('seedLegacy');
stop();
const child = spawn(adbPath, ['-s',serial,...testArgs('interruptAtPublish')]);
let output = '';
child.stdout.on('data', x => output += x);
child.stderr.on('data', x => output += x);
const ended = new Promise(r => child.on('close', r));
try {
  let ready = false;
  for (let i=0;i<90;i++) {
    if (child.exitCode !== null) throw Error(`Test exited before checkpoint: ${output}`);
    try { ready = shell('run-as',pkg,'cat','no_backup/migration-device-at-publish').trim() === 'ready'; } catch {}
    if (ready) break;
    await delay(1000);
  }
  assert.ok(ready, 'Publish checkpoint did not arrive');
  const pid = shell('pidof',pkg).trim();
  assert.match(pid,/^\d+$/);
  writeFileSync(`${evidence}/interrupted-pid.txt`,pid);
  stop();
  await ended;
  assert.match(output,/Process crashed|Process was killed|INSTRUMENTATION_FAILED/);
  writeFileSync(`${evidence}/interruptAtPublish.txt`,output);
} finally { if(child.exitCode===null) {stop(); await ended;} }
test('verifyPendingAfterDeath');
stop();
assert.match(adb(['install','-r','-t',apk]),/Success/);
test('verifyPendingAfterDeath');
stop();
shell('input','keyevent','KEYCODE_WAKEUP');
shell('wm','dismiss-keyguard');
shell('am','start','-W','-n','phantom.android/.MainActivity');
await waitUi('Security update','pending-ui');
tap(await waitUi('Continue','continue-ui'));
await waitUi('Retry','offline-retry-ui');
const errorNodes = uiNodes('offline-error-ui');
assert.ok(errorNodes.some(n=>n.text==='The server could not confirm the update. Check your connection and retry.' ||
  n.text==='The update could not finish. Retry without reinstalling or clearing app data. If it keeps failing, contact support.'));
test('verifyPendingAfterDeath');
test('finishWithControlledRelayResponse');
stop();
assert.match(adb(['install','-r','-t',apk]),/Success/);
test('verifyCompletedAfterReplacement');
stop();
writeFileSync(`${root}/latest-run.txt`,evidence+'\n');
console.log(`DEVICE_MIGRATION_PASS evidence=${evidence}`);
