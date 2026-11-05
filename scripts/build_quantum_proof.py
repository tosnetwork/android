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
import sys

# Expanded immutable revision is read from the checked-in pin, never an endpoint.

def require_source_checkout(source):
    # git -C otherwise walks into the enclosing Android repository when a
    # partially cleaned source cache has lost its own Git metadata.
    root = subprocess.check_output(
        ['git', '-C', str(source), 'rev-parse', '--show-toplevel'], text=True
    ).strip()
    if Path(root).resolve() != source.resolve():
        raise RuntimeError(
            'Proof source is not an independent Git checkout: ' + str(source)
            + '. Move the incomplete cache aside and retry, or set TOS_PROOF_ROOT '
            'to a clean checkout of the pinned TOS revision.'
        )

def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], required=True)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    revision = (repository / 'scripts/quantum-proof-revision.txt').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise RuntimeError('Expected an immutable 40-character source revision')
    default_source = (repository / '.gradle/quantum-proof-source').resolve()
    source = Path(os.environ.get('TOS_PROOF_ROOT', default_source)).resolve()
    if source.exists():
        require_source_checkout(source)
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--filter=blob:none', '--no-checkout', 'https://github.com/tosnetwork/tos.git', str(source)], check=True)
        subprocess.run(['git', '-C', str(source), 'checkout', '--detach', revision], check=True)
        subprocess.run(['git', '-C', str(source), 'submodule', 'update', '--init', '--recursive'], check=True)
    head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    dirty = subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip()
    if dirty:
        raise RuntimeError('Proof source contains local changes')
    if head != revision and source == default_source:
        subprocess.run(['git', '-C', str(source), 'fetch', 'origin', revision], check=True)
        subprocess.run(['git', '-C', str(source), 'checkout', '--detach', revision], check=True)
        subprocess.run(['git', '-C', str(source), 'submodule', 'update', '--init', '--recursive'], check=True)
        head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if head != revision:
        raise RuntimeError('Provided proof source does not match the pinned revision')
    work = Path(os.environ.get('TOS_PROOF_BUILD_ROOT', repository / '.gradle/quantum-proof-build')).resolve()
    host_name = 'host-apple' if sys.platform == 'darwin' else 'host'
    host = Path(os.environ.get('TOS_PROOF_HOST_BUILD', work / host_name)).resolve()
    build_env = os.environ.copy()
    if sys.platform == 'darwin':
        compilers = [
            subprocess.check_output(['xcrun', '--sdk', 'macosx', '--find', name], text=True).strip()
            for name in ('clang', 'clang++')
        ]
        sdk = subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], text=True).strip()
        build_env = {**os.environ, 'PATH': str(Path(compilers[0]).parent) + os.pathsep + os.environ['PATH']}
        if not (host / 'build.ninja').exists():
            openssl = host / 'third-party' / 'openssl'
            configure = [
                'cmake', '-S', str(source), '-B', str(host), '-G', 'Ninja',
                '-DCMAKE_BUILD_TYPE=Release', '-DTOS_USE_ROCKSDB=OFF',
                '-DTOS_USE_ABSEIL=OFF', '-DUSE_QUIC=OFF',
                f'-DCMAKE_C_COMPILER={compilers[0]}', f'-DCMAKE_CXX_COMPILER={compilers[1]}',
                f'-DCMAKE_OSX_SYSROOT={sdk}',
            ]
            openssl_args = [
                f'-DOPENSSL_ROOT_DIR={openssl}',
                f'-DOPENSSL_CRYPTO_LIBRARY={openssl / "lib/libcrypto.a"}',
                f'-DOPENSSL_SSL_LIBRARY={openssl / "lib/libssl.a"}',
                f'-DOPENSSL_INCLUDE_DIR={openssl / "include"}',
            ]
            host_env = {**build_env, 'SDKROOT': sdk}
            if not (openssl / 'lib/libcrypto.a').is_file():
                result = subprocess.run(configure + ['-UOPENSSL_*'], env=host_env)
                if result.returncode and not (openssl / 'lib/libcrypto.a').is_file():
                    result.check_returncode()
            subprocess.run(configure + openssl_args, check=True, env=host_env)
    elif not (host / 'build.ninja').exists():
        openssl = host / 'third-party' / 'openssl'
        configure = [
            'cmake', '-S', str(source), '-B', str(host), '-G', 'Ninja',
            '-DCMAKE_BUILD_TYPE=Release', '-DTOS_USE_ROCKSDB=OFF',
            '-DTOS_USE_ABSEIL=OFF', '-DUSE_QUIC=OFF',
        ]
        crypto = openssl / 'lib/libcrypto.a'
        if not crypto.is_file():
            result = subprocess.run(configure + ['-UOPENSSL_*'])
            if result.returncode and not crypto.is_file():
                result.check_returncode()
        subprocess.run(configure + [
            f'-DOPENSSL_ROOT_DIR={openssl}',
            f'-DOPENSSL_CRYPTO_LIBRARY={crypto}',
            f'-DOPENSSL_SSL_LIBRARY={openssl / "lib/libssl.a"}',
            f'-DOPENSSL_INCLUDE_DIR={openssl / "include"}',
        ], check=True)
    target = work / args.abi
    jobs = int(os.environ.get('TOS_PROOF_BUILD_JOBS', '4'))
    if not 1 <= jobs <= 32: raise RuntimeError('Proof build jobs must be in 1..32')
    subprocess.run(['python3', str(source / 'scripts/build-embedded-proof-android.py'), '--ndk', str(args.ndk), '--host-build', str(host), '--build-dir', str(target), '--abi', args.abi, '--jobs', str(jobs)], check=True, env=build_env)
    library = target / 'lite-client/proof-verify/libtosproofverify.so'
    destination = args.output.resolve() / args.abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, destination / library.name)
    # Client-owned JNI adapter links the pinned portable C ABI directly. No
    # dynamic symbol lookup or older-code fallback can bypass hash binding.
    host_tag = 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    compiler = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host_tag / 'bin/clang++'
    triple = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7a-linux-androideabi',
              'x86': 'i686-linux-android', 'x86_64': 'x86_64-linux-android'}[args.abi]
    adapter = destination / 'libtoswalletreceipt.so'
    subprocess.run([str(compiler), '--target=' + triple + '24', '-std=c++17', '-O2',
                    '-fPIC', '-shared', '-static-libstdc++', '-fvisibility=hidden',
                    '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2', '-Wall', '-Wextra', '-Werror',
                    '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
                    '-Wl,--exclude-libs,ALL', '-Wl,--build-id=sha1',
                    '-Wl,-z,relro,-z,now', '-Wl,-z,noexecstack',
                    '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384',
                    '-Wl,-z,common-page-size=16384', '-Wl,-soname,libtoswalletreceipt.so',
                    '-I' + str(source / 'lite-client/proof-verify'),
                    str(repository / 'lib/security/src/main/cpp/wallet_receipt_jni.cpp'),
                    '-L' + str(destination), '-ltosproofverify', '-o', str(adapter)], check=True)
    (destination / 'PROVENANCE.json').write_text(json.dumps({'revision': head, 'abi': args.abi, 'sha256': hashlib.sha256(library.read_bytes()).hexdigest(), 'receipt_adapter_sha256': hashlib.sha256(adapter.read_bytes()).hexdigest()}, indent=2) + '\n')

def main():
    # Gradle can execute independent Exec tasks concurrently even with project
    # parallelism disabled. All ABI builders share one host generator graph.
    directory = Path(__file__).resolve().parents[1] / '.gradle'
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / 'quantum-proof-build.lock').open('a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        build()


if __name__ == '__main__':
    main()
