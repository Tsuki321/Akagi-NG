"""Create a persistent APK signing identity and store it in this fork's CI secrets.

The local recovery copy stays in ignored artifacts/signing. This performs key
management only; APK compilation and signing happen in GitHub Actions.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets
import subprocess

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization.pkcs12 import serialize_key_and_certificates
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    args = parser.parse_args()
    directory = ROOT / 'artifacts/signing'
    directory.mkdir(parents=True, exist_ok=True)
    keystore = directory / 'akagi-android.p12'
    credentials = directory / 'recovery.json'
    if not keystore.exists() and not credentials.exists():
        password = secrets.token_urlsafe(36)
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Akagi Android')])
        now = datetime.now(timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=10000))
                       .sign(key, hashes.SHA256()))
        keystore.write_bytes(serialize_key_and_certificates(
            b'akagi', key, certificate, None, serialization.BestAvailableEncryption(password.encode())))
        credentials.write_text(json.dumps({'alias': 'akagi', 'password': password,
                                           'certificate_sha256': certificate.fingerprint(hashes.SHA256()).hex()}) + '\n')
    if not keystore.exists() or not credentials.exists():
        raise SystemExit('Incomplete signing recovery files. Restore them before continuing; never replace the existing identity.')
    metadata = json.loads(credentials.read_text())
    values = {
        'AKAGI_ANDROID_KEYSTORE_BASE64': base64.b64encode(keystore.read_bytes()).decode(),
        'AKAGI_ANDROID_KEYSTORE_PASSWORD': metadata['password'],
    }
    for name, value in values.items():
        subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo], input=value, text=True, check=True)
    print('Signing identity saved locally and uploaded to the specified repository secrets. No key material is logged.')
    print('Certificate SHA-256:', metadata['certificate_sha256'])


if __name__ == '__main__':
    main()
