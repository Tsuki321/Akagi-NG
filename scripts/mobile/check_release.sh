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
adb push artifacts/release-model-imports/replacement-4p.akagimodel /sdcard/Download/Replacement-4p.akagimodel
adb push artifacts/release-model-imports/replacement-3p.akagimodel /sdcard/Download/Replacement-3p.akagimodel
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

def find(match):
    for node in nodes():
        if match(node.attrib) and node.attrib.get('enabled') != 'false':
            x1,y1,x2,y2 = map(int, re.findall(r'\d+',node.attrib['bounds']))
            if x2 > x1 and y2 > y1:
                return node
    return None


def tap(node):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+',node.attrib['bounds']))
    subprocess.run(['adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)


def click(match):
    node = find(match)
    if node is not None:
        tap(node)
        return True
    return False


def wait_click(match, description, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if click(match):
            return
        time.sleep(.5)
    raise AssertionError(f'Cannot reach {description}')


def swipe(down):
    start, end = ('850', '330') if down else ('300', '800')
    subprocess.run(['adb','shell','input','swipe','1100',start,'1100',end,'350'],check=True)


def reveal(match, down=True):
    for _ in range(18):
        node = find(match)
        if node is not None:
            return node
        swipe(down)
    raise AssertionError('Cannot reach release model control')


def reveal_click(match, down=True):
    tap(reveal(match, down))
    time.sleep(.5)


def wait_text(expected, scroll_down=False):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        content = ' '.join(n.attrib.get('text','') for n in nodes())
        if expected in content:
            return content
        assert 'Model was not changed.' not in content, content
        assert 'Local AI check failed' not in content, content
        if scroll_down:
            swipe(True)
        time.sleep(.5)
    raise AssertionError(f'Release did not show {expected}')


def select_bundle(players):
    name = f'Replacement-{players}p.akagimodel'
    time.sleep(1)
    if click(lambda a: a.get('text') == name):
        return
    wait_click(lambda a: a.get('content-desc') in ('Show roots', 'Open navigation drawer'), 'document locations')
    # DocumentsUI discovers providers and loads directory entries asynchronously.
    # A populated drawer header does not imply its roots have appeared yet.
    wait_click(lambda a: a.get('text') == 'Downloads', 'Downloads in file picker')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if click(lambda a: a.get('text') == name):
            return
        # Landscape grid tiles can put their filenames below the visible area.
        # List mode exposes the filename instead of a preview-only control.
        click(lambda a: a.get('content-desc') == 'List view')
        time.sleep(.5)
    raise AssertionError(f'Cannot choose {name}')


def verify_selected_models():
    reveal_click(lambda a: a.get('text') == 'Check local model')
    content = wait_text('Local AI is ready.', scroll_down=True)
    assert '62 desktop reference observations passed' in content, content
    return content


def open_settings():
    assert click(lambda a: a.get('content-desc','').startswith('Open Akagi advice'))
    assert click(lambda a: a.get('content-desc') == 'Open settings')

# The system owns its first-use fullscreen education. Acknowledge it as a user.
for _ in range(5):
    if click(lambda a: a.get('text','').lower()=='got it' and a.get('package','') in ('android','com.android.systemui')):
        break
    time.sleep(1)
assert click(lambda a: a.get('content-desc','').startswith('Open Akagi advice')), 'Missing compact advice control'
time.sleep(1)
assert click(lambda a: a.get('content-desc')=='Open settings'), 'Missing settings control'
time.sleep(1)
for players, mode in ((4, 'four-player'), (3, 'three-player')):
    reveal_click(lambda a: a.get('content-desc') == f'Import {mode} model')
    select_bundle(players)
    content = wait_text(f'{players}-player model updated.')
    (out/f'import-{players}p.txt').write_text(content)
    with (out/f'release-import-{players}p.png').open('wb') as screenshot:
        subprocess.run(['adb','exec-out','screencap','-p'],check=True,stdout=screenshot)
    # A check after each import verifies the remaining mode and the replacement.
    (out/f'import-{players}p-model-check.txt').write_text(verify_selected_models())
    if players == 4:
        # Relaunch also checks private-copy persistence, without retaining a URI grant.
        subprocess.run(['adb','shell','am','force-stop','org.akagi.mobile'],check=True)
        subprocess.run(['adb','shell','am','start','-n','org.akagi.mobile/.MainActivity'],check=True)
        time.sleep(2)
        open_settings()
        reveal(lambda a: a.get('text') == 'Replacement 4p')
        (out/'persisted-4p.txt').write_text('Replacement 4p remained selected after force-stop and relaunch.\n')

# Return only 4p to its default. The selected 3p checkpoint must still pass.
reveal_click(lambda a: a.get('content-desc') == 'Use bundled four-player model', down=False)
reveal(lambda a: a.get('text') == 'Bundled 4-player model restored.', down=False)
(out/'restored-4p.txt').write_text(' '.join(n.attrib.get('text','') for n in nodes()))
reveal(lambda a: a.get('text') == 'Replacement 3p')
content = ' '.join(n.attrib.get('text','') for n in nodes())
assert 'Replacement 3p' in content, content
(out/'independent-model-reset.txt').write_text(verify_selected_models())
reveal_click(lambda a: a.get('content-desc') == 'Use bundled three-player model', down=False)

for _ in range(16):
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
for _ in range(16):
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
for _ in range(16):
    if click(lambda a: a.get('content-desc')=='Close settings'):
        break
    subprocess.run(['adb','shell','input','swipe','1100','300','1100','800','350'],check=True)
else:
    raise AssertionError('Cannot reach Close settings')
time.sleep(1)
assert click(lambda a: a.get('content-desc')=='Collapse advice'), 'Cannot restore compact game'
print('PASS: signed release imported each model through Android DocumentsUI, retained independent choices after relaunch and reset, passed real inference, and restored compact controls.')
PY
# Reinstall the identical signed APK to exercise Android's update path.
adb install -r "$apk"
adb shell am start -n org.akagi.mobile/.MainActivity
sleep 45
adb exec-out screencap -p > "$out/release-game.png"
adb shell pidof org.akagi.mobile > "$out/process.txt"
