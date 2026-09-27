#!/usr/bin/env bash
set -euo pipefail
out=artifacts/release-device
mkdir -p "$out"
collect() {
  adb logcat -d > "$out/logcat.txt" || true
  adb exec-out screencap -p > "$out/final.png" || true
}
trap collect EXIT
apk=artifacts/distribution/Akagi-Android-16.apk
adb install -r "$apk"
adb shell wm size 1080x2340
adb shell wm density 420
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell input keyevent 82
adb logcat -c
adb shell am start -n org.akagi.mobile/.MainActivity
sleep 4
python - <<'PY'
import re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path

out = Path('artifacts/release-device')

def nodes():
    last_error = None
    for attempt in range(3):
        # A failed dump can leave an old file behind while adb still returns 0.
        # Each attempt reads only its own new filename, never a previous dump.
        remote = f'/sdcard/release-window-{time.monotonic_ns()}-{attempt}.xml'
        try:
            subprocess.run(['adb','shell','uiautomator','dump',remote], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=30)
            raw = subprocess.check_output(['adb','shell','cat',remote], text=True,
                                          stderr=subprocess.PIPE, timeout=20)
            root = ET.fromstring(raw)
            result = list(root.iter('node'))
            if root.tag != 'hierarchy' or not result:
                raise ValueError('UI dump did not contain a populated hierarchy')
            (out/'window.xml').write_text(raw, encoding='utf-8')
            return result
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError, ValueError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(1)
    raise AssertionError('Could not read a fresh valid UI hierarchy after 3 attempts') from last_error

def click(match):
    for node in nodes():
        if match(node.attrib):
            x1,y1,x2,y2 = map(int, re.findall(r'\d+',node.attrib['bounds']))
            subprocess.run(['adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)
            return True
    return False

# The system owns its first-use fullscreen education. Acknowledge it as a user.
for _ in range(5):
    if click(lambda a: a.get('text','').lower()=='got it' and a.get('package','') in ('android','com.android.systemui')):
        break
    time.sleep(1)
assert click(lambda a: a.get('content-desc','').startswith('Open Akagi advice')), 'Missing compact advice control'
time.sleep(1)
assert click(lambda a: a.get('content-desc')=='Open settings'), 'Missing settings control'
time.sleep(1)
for _ in range(7):
    if click(lambda a: a.get('text')=='Check local model'):
        break
    subprocess.run(['adb','shell','input','swipe','1100','850','1100','330','350'],check=True)
else:
    raise AssertionError('Cannot reach Check local model in signed release')
deadline=time.monotonic()+60
while time.monotonic()<deadline:
    text=' '.join(n.attrib.get('text','') for n in nodes())
    if 'Local AI is ready.' in text:
        (out/'model-check.txt').write_text(text)
        break
    assert 'Local AI check failed' not in text, text
    subprocess.run(['adb','shell','input','swipe','1100','850','1100','330','350'],check=True)
    time.sleep(1)
else:
    raise AssertionError('Signed release model check did not finish')
with (out/'release-model-check.png').open('wb') as screenshot:
    subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=screenshot)
for _ in range(7):
    if click(lambda a: a.get('text')=='Check saved hand'):
        break
    subprocess.run(['adb','shell','input','swipe','1100','300','1100','800','350'],check=True)
else:
    raise AssertionError('Cannot reach Check saved hand')
deadline=time.monotonic()+60
while time.monotonic()<deadline:
    text=' '.join(n.attrib.get('text','') for n in nodes())
    if 'Saved hand: Discard 7s' in text:
        (out/'native-replay-check.txt').write_text(text)
        break
    assert 'Saved-hand check failed' not in text, text
    subprocess.run(['adb','shell','input','swipe','1100','850','1100','330','350'],check=True)
    time.sleep(1)
else:
    raise AssertionError('Signed release native replay did not return the reference decision')
for _ in range(7):
    if click(lambda a: a.get('content-desc')=='Close settings'):
        break
    subprocess.run(['adb','shell','input','swipe','1100','300','1100','800','350'],check=True)
else:
    raise AssertionError('Cannot reach Close settings')
time.sleep(1)
assert click(lambda a: a.get('content-desc')=='Collapse advice'), 'Cannot restore compact game'
print('PASS: signed release installed, real local models passed, compact controls work.')
PY
# Reinstall the identical signed APK to exercise Android's update path.
adb install -r "$apk"
adb shell am start -n org.akagi.mobile/.MainActivity
sleep 45
adb exec-out screencap -p > "$out/release-game.png"
adb shell pidof org.akagi.mobile > "$out/process.txt"
