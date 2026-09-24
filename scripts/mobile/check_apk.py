"""Verify packaged native code, bundled models and 16 KB alignment in CI."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
sdk = Path(os.environ['ANDROID_HOME'])
zipalign = sdk / 'build-tools/36.0.0/zipalign'
subprocess.run([str(zipalign), '-c', '-P', '16', '-v', '4', str(apk)], check=True)
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    for abi in ('arm64-v8a', 'x86_64'):
        assert f'lib/{abi}/libakagi_mortal.so' in names, f'Missing native core: {abi}'
    for model in ('mortal4p.json', 'mortal3p.json'):
        assert f'assets/models/{model}' in names, f'Missing model: {model}'
    assert 'assets/protocol/liqi.json' in names
    count = 0
    for name in names:
        if not name.startswith('lib/') or not name.endswith('.so'):
            continue
        data = archive.read(name)
        assert data[:4] == b'\x7fELF' and data[4:6] == b'\x02\x01', name
        phoff = struct.unpack_from('<Q', data, 32)[0]
        phsize, phnum = struct.unpack_from('<HH', data, 54)
        for i in range(phnum):
            ptype = struct.unpack_from('<I', data, phoff + i * phsize)[0]
            if ptype == 1:
                alignment = struct.unpack_from('<Q', data, phoff + i * phsize + 48)[0]
                assert alignment >= 16384, f'{name} has insufficient PT_LOAD alignment: {alignment}'
        count += 1
    print(f'PASS: {count} native libraries have 16 KB ELF alignment; APK and bundled assets verified.')
