#!/usr/bin/env python3
"""Build an allowlisted context and record Docker's actual immutable image ID."""
import io
import pathlib
import re
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def build():
    image = re.search(r'^JAVA_IMAGE=(.+)$', (ROOT / 'config/images.lock').read_text(), re.M).group(1)
    checksum = re.search(r'^distributionSha256Sum=([a-f0-9]{64})$', (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text(), re.M).group(1)
    entries = [(f'runner/{name}', ROOT / 'runner' / name) for name in (
        'Dockerfile', 'CommandMain.java', 'stage.sh', 'build.gradle', 'gradle.properties', 'commands.json')]
    entries += [(f'starter/{name}', ROOT / 'benchmarks/task-tracker-v1/starter' / name) for name in (
        'build.gradle', 'settings.gradle', 'gradle.lockfile', 'gradle/verification-metadata.xml')]
    context = io.BytesIO()
    with tarfile.open(fileobj=context, mode='w:gz') as archive:
        for name, path in entries:
            if not path.is_file() or any(part.is_symlink() for part in (path, *path.parents)):
                raise ValueError(f'Invalid build input: {name}')
            archive.add(path, arcname=name, recursive=False)
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    iid = target / 'runner-image.iid'
    iid.unlink(missing_ok=True)
    subprocess.run(['docker', 'build', '--load', '--file', 'runner/Dockerfile', '--build-arg', f'JAVA_IMAGE={image}', '--build-arg', f'GRADLE_SHA256={checksum}', '--iidfile', str(iid), '-'], input=context.getvalue(), check=True)
    resolved = iid.read_text().strip()
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', resolved):
        raise ValueError('Docker returned an invalid image ID')
    inspect = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}} {{.Config.User}}', resolved], text=True).strip()
    if inspect != f'{resolved} 1000:1000':
        raise ValueError('Image identity or configured user mismatch')
    (ROOT / 'runner/image.lock').write_text(f'# Actual locally built immutable image ID. Rebuild with scripts/build-runner-image.py.\nRUNNER_IMAGE={resolved}\n')
    print(resolved)

if __name__ == '__main__':
    build()
