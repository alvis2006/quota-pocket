"""Build the dependency-free Android app using a local JDK and Android SDK.

python tools/build_apk.py --java-home PATH --android-jar PATH --build-tools PATH
No SDK installation, account access, or system configuration changes are performed.
"""
import argparse
import pathlib
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(*args):
    # Some Windows Android binaries cannot decode non-ASCII command-line paths.
    # The workspace itself may contain Chinese characters; use relative arguments.
    def argument(value):
        if isinstance(value, pathlib.Path):
            try:
                return str(value.relative_to(ROOT))
            except ValueError:
                pass
        return str(value)
    subprocess.run([argument(a) for a in args], cwd=ROOT, check=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--java-home', type=pathlib.Path, required=True)
    p.add_argument('--android-jar', type=pathlib.Path, required=True)
    p.add_argument('--build-tools', type=pathlib.Path, required=True)
    args = p.parse_args()
    java = args.java_home.resolve() / 'bin'
    sdk = args.build_tools.resolve()
    android = args.android_jar.resolve()
    build = ROOT / 'app/build/local'
    gen, classes, dex = [build / name for name in ('gen', 'classes', 'dex')]
    for folder in (gen, classes, dex, ROOT / 'output'):
        folder.mkdir(parents=True, exist_ok=True)
    ns = 'http://schemas.android.com/apk/res/android'
    ET.register_namespace('android', ns)
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
    manifest.getroot().set('package', 'com.gather.quotapocket')
    manifest.write(build / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    run(sdk / 'aapt2.exe', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', build / 'resources.zip')
    run(sdk / 'aapt2.exe', 'link', '-I', android, '--manifest', build / 'AndroidManifest.xml',
        '--java', gen, '--min-sdk-version', '26', '--target-sdk-version', '35',
        '--version-code', '5', '--version-name', '0.5.0', '-o', build / 'resources.apk', build / 'resources.zip')
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted(gen.rglob('*.java'))
    source_list = build / 'sources.txt'
    source_list.write_text('\n'.join('"' + f.relative_to(ROOT).as_posix() + '"' for f in sources), encoding='utf-8')
    run(java / 'javac.exe', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '17', '-classpath', android,
        '-d', classes, '@' + str(source_list.relative_to(ROOT)))
    with zipfile.ZipFile(build / 'classes.jar', 'w') as jar:
        for f in classes.rglob('*.class'):
            jar.write(f, f.relative_to(classes).as_posix())
    run(java / 'java.exe', '-cp', sdk / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android,
        '--min-api', '26', '--output', dex, build / 'classes.jar')
    with zipfile.ZipFile(build / 'resources.apk') as original, zipfile.ZipFile(build / 'unsigned.apk', 'w') as apk:
        for item in original.infolist():
            apk.writestr(item, original.read(item.filename))
        for f in dex.glob('*.dex'):
            apk.write(f, f.name)
    run(sdk / 'zipalign.exe', '-f', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk')
    key = ROOT / '.tools/debug.keystore'
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        run(java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=Quota Pocket Development,O=Development,C=MY')
    output = ROOT / 'output/QuotaPocket-debug.apk'
    run(java / 'java.exe', '-jar', sdk / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'androiddebugkey',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, build / 'aligned.apk')
    run(java / 'java.exe', '-jar', sdk / 'lib/apksigner.jar', 'verify', '--verbose', output)
    print('APK:', output)


if __name__ == '__main__':
    main()
