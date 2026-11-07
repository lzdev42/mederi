#!/usr/bin/env python3
"""Release assembly regression checks; fake Gradle/JBR, never starts the app/server."""
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class ReleasePackagingTest(unittest.TestCase):
    def test_platform_packages(self):
        targets = [
            ('macos', 'Darwin', 'x86_64', 'x64'),
            ('linux', 'Linux', 'x86_64', 'x64'),
            ('linux', 'Linux', 'aarch64', 'arm64'),
            ('windows', 'MINGW64_NT', 'x86_64', 'x64'),
        ]
        for platform, uname, machine, arch in targets:
            with self.subTest(platform=platform, arch=arch), tempfile.TemporaryDirectory(dir=ROOT / 'temp') as scratch:
                root = Path(scratch)
                shutil.copy(ROOT / 'build_release.sh', root)
                tools = root / 'tools'
                tools.mkdir()

                def executable(path, body):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(body)
                    path.chmod(0o755)

                executable(tools / 'uname', f'#!/bin/sh\n[ "$1" = -m ] && echo {machine} || echo {uname}\n')
                executable(tools / 'cygpath', '#!/bin/sh\nprintf "%s\\n" "$2"\n')
                jbr = root / 'jbr'
                java_home = jbr / 'Contents/Home' if platform == 'macos' else jbr
                (java_home / 'jmods').mkdir(parents=True)
                (java_home / 'jmods/java.base.jmod').touch()
                (java_home / 'jmods/jcef.jmod').touch()
                server_java = root / 'server-java'
                (server_java / 'jmods').mkdir(parents=True)
                (server_java / 'jmods/java.base.jmod').touch()
                if platform == 'macos':
                    (jbr / 'Contents/Frameworks/cef_server.app').mkdir(parents=True)
                # Real ZIP assembly with the same CLI shape as jar, so archive contents can be checked.
                executable(java_home / 'bin/jar', '''#!/usr/bin/env python3
import sys, zipfile
from pathlib import Path
args = sys.argv
out = args[args.index('--file') + 1]
base = Path(args[args.index('-C') + 1])
folder = base / args[-1]
with zipfile.ZipFile(out, 'w') as z:
    for p in folder.rglob('*'):
        if p.is_file(): z.write(p, p.relative_to(base))
''')
                executable(root / 'gradlew', '''#!/usr/bin/env bash
set -e
printf '%s\\n' "$@" >> gradle-calls
if [[ " $* " == *createReleaseDistributable* ]]; then
    test -f "$JAVA_HOME/jmods/jcef.jmod"
    parent=app/desktopApp/build/compose/binaries/main-release/app
    if [[ "$FAKE_PLATFORM" == macos ]]; then
        mkdir -p "$parent/Mederi.app/Contents"
        touch "$parent/Mederi.app/Contents/Info.plist"
    else
        mkdir -p "$parent/Mederi/bin"
        touch "$parent/Mederi/bin/Mederi"
    fi
fi
if [[ " $* " == *installServerBundled* ]]; then
    test "$JAVA_HOME" = "$MEDERI_SERVER_JAVA_HOME"
    for variant in bundled api; do
        mkdir -p "server/build/install/server-$variant/bin" "server/build/install/server-$variant/lib"
        touch "server/build/install/server-$variant/lib/$variant.jar"
    done
    mkdir -p server/build/install/server-bundled/runtime
    touch server/build/install/server-bundled/runtime/release
fi
''')
                env = dict(os.environ, JAVA_HOME=str(java_home), FAKE_PLATFORM=platform,
                           MEDERI_SERVER_JAVA_HOME=str(server_java),
                           PATH=f'{tools}:{os.environ["PATH"]}')
                result = subprocess.run(['bash', str(root / 'build_release.sh'), '--skip-sign',
                                         '--output', 'relative output'], cwd=root, env=env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                output = root / 'relative output'
                suffix = '.zip' if platform == 'windows' else '.tar.gz'
                for variant in ['bundled', 'api']:
                    stem = f'Mederi-server-{variant}-{platform}-{arch}'
                    archive = output / (stem + suffix)
                    if platform == 'windows':
                        with zipfile.ZipFile(archive) as z:
                            names = z.namelist()
                            launcher = z.read(stem + '/run.bat').decode()
                            self.assertIn('bin\\', launcher)
                            self.assertIn('\r\n', launcher)
                    else:
                        with tarfile.open(archive) as t:
                            names = t.getnames()
                            launcher = t.extractfile(stem + '/run.sh').read().decode()
                            self.assertTrue(t.getmember(stem + '/run.sh').mode & 0o111)
                            subprocess.run(['sh', '-n'], input=launcher, text=True, check=True)
                    self.assertEqual('JAVA_HOME' in launcher, variant == 'bundled')
                    if variant == 'bundled':
                        self.assertIn(stem + '/runtime/release', names)
                        self.assertNotIn('Contents/Home', launcher)
                    else:
                        self.assertFalse(any('/runtime' in n for n in names))
                if platform == 'macos':
                    self.assertTrue((output / 'Mederi.app').is_dir())
                    self.assertFalse(list(output.glob('*.dmg')))
                else:
                    self.assertTrue((output / f'Mederi-desktop-{platform}-{arch}{suffix}').is_file())
                calls = (root / 'gradle-calls').read_text()
                self.assertIn(':server:installServerApi', calls)
                self.assertIn(f'-Pmederi.serverJavaHome={server_java}', calls)
                self.assertNotIn('packageReleaseDistributionForCurrentOS', calls)
                self.assertEqual(list((root / 'temp').iterdir()), [])

                env['MEDERI_SERVER_JAVA_HOME'] = str(java_home)
                rejected = subprocess.run(['bash', str(root / 'build_release.sh'), '--skip-sign'],
                                          cwd=root, env=env, capture_output=True, text=True)
                self.assertNotEqual(rejected.returncode, 0)
                self.assertIn('MEDERI_SERVER_JAVA_HOME', rejected.stdout)


if __name__ == '__main__':
    (ROOT / 'temp').mkdir(exist_ok=True)
    unittest.main()
