#!/usr/bin/env python3
"""Build a trusted, allowlisted repository-check image on the benchmark runner base."""
import io
import pathlib
import re
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def build():
    base = re.search(r'^RUNNER_IMAGE=(sha256:[0-9a-f]{64})$',
                     (ROOT / 'runner/image.lock').read_text(), re.M).group(1)
    files = {
        'runner/repository.Dockerfile': ROOT / 'runner/repository.Dockerfile',
        'repository/build.gradle': ROOT / 'runner/repository-build.gradle',
        'repository/settings.gradle': ROOT / 'settings.gradle',
        'repository/gradle.lockfile': ROOT / 'gradle.lockfile',
        'repository/gradle.properties': ROOT / 'runner/gradle.properties',
        'repository/gradle/verification-metadata.xml': ROOT / 'gradle/verification-metadata.xml',
    }
    context = io.BytesIO()
    with tarfile.open(fileobj=context, mode='w:gz') as archive:
        for name, path in files.items():
            if not path.is_file() or any(part.is_symlink() for part in (path, *path.parents)):
                raise ValueError(f'Invalid build input: {name}')
            archive.add(path, arcname=name, recursive=False)
    iid = ROOT / 'target/repository-runner-image.iid'
    iid.parent.mkdir(exist_ok=True)
    iid.unlink(missing_ok=True)
    local_tag = 'continuous-agent-harness-repository-base:local'
    subprocess.run(['docker', 'tag', base, local_tag], check=True)
    subprocess.run(['docker', 'build', '--load', '--pull=false', '--network=host', '--file', 'runner/repository.Dockerfile',
                    '--build-arg', f'BENCHMARK_IMAGE={local_tag}', '--iidfile', str(iid), '-'],
                   input=context.getvalue(), check=True)
    image = iid.read_text().strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise ValueError('Docker returned an invalid image ID')
    (ROOT / 'runner/repository-image.lock').write_text(f'REPOSITORY_RUNNER_IMAGE={image}\n')
    print(image)

if __name__ == '__main__':
    build()
