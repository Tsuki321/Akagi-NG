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
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/release-window.xml'], check=True, stdout=subprocess.DEVNULL)
    raw = subprocess.check_output(['adb','shell','cat','/sdcard/release-window.xml'], text=True)
    (out/'window.xml').write_text(raw)
    return list(ET.fromstring(raw).iter('node'))

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
    time.sleep(1)
else:
    raise AssertionError('Signed release model check did not finish')
with (out/'release-model-check.png').open('wb') as screenshot:
    subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=screenshot)
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
