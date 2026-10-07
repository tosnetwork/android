#!/usr/bin/env python3
"""Build pinned TOS proof libraries for Android application packaging."""
import argparse
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess

# Expanded immutable revision is read from the checked-in pin, never an endpoint.

def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], required=True)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    revision = (repository / 'scripts/v5r2-proof-revision.txt').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise RuntimeError('Expected an immutable 40-character source revision')
    source = Path(os.environ.get('TOS_PROOF_ROOT', repository / '.gradle/v5r2-proof-source')).resolve()
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--filter=blob:none', '--no-checkout', 'https://github.com/tosnetwork/tos.git', str(source)], check=True)
        subprocess.run(['git', '-C', str(source), 'checkout', '--detach', revision], check=True)
        subprocess.run(['git', '-C', str(source), 'submodule', 'update', '--init', '--recursive'], check=True)
    head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if head != revision or subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip():
        raise RuntimeError('Proof source must be clean and match the pinned revision')
    work = Path(os.environ.get('TOS_PROOF_BUILD_ROOT', repository / '.gradle/v5r2-proof-build')).resolve()
    host = Path(os.environ.get('TOS_PROOF_HOST_BUILD', work / 'host')).resolve()
    if not (host / 'CMakeCache.txt').exists():
        subprocess.run(['cmake', '-S', str(source), '-B', str(host), '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DTOS_USE_ROCKSDB=OFF', '-DTOS_USE_ABSEIL=OFF'], check=True)
    target = work / args.abi
    subprocess.run(['python3', str(source / 'scripts/build-embedded-proof-android.py'), '--ndk', str(args.ndk), '--host-build', str(host), '--build-dir', str(target), '--abi', args.abi], check=True)
    library = target / 'lite-client/proof-verify/libtosproofverify.so'
    destination = args.output.resolve() / args.abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, destination / library.name)
    (destination / 'PROVENANCE.json').write_text(json.dumps({'revision': head, 'abi': args.abi, 'sha256': hashlib.sha256(library.read_bytes()).hexdigest()}, indent=2) + '\n')

def main():
    # Gradle can execute independent Exec tasks concurrently even with project
    # parallelism disabled. All ABI builders share one host generator graph.
    directory = Path(__file__).resolve().parents[1] / '.gradle'
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / 'v5r2-proof-build.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        build()


if __name__ == '__main__':
    main()
