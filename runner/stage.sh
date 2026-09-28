#!/bin/sh
set -eu
exec 9>/control/stage.lock
flock -x 9
if test -f /control/ready; then
    cat >/dev/null
    exit 0
fi
mkdir -p /work/input
# Parent supplies an independently validated archive of regular files/directories.
# No candidate process is started until this root-owned readiness marker exists.
find /work/input -type d -exec chmod 0755 {} +
find /work/input -type f -exec chmod 0644 {} +
tar --extract --file=- --directory=/work/input --no-same-owner --no-same-permissions
if find /work/input ! -type f ! -type d -print -quit | grep -q .; then
    exit 65
fi
chown -R 0:0 /work/input
find /work/input -type f -exec chmod 0444 {} +
find /work/input -type d -exec chmod 0555 {} +
mkdir -p /work/project/gradle
# Recover an interrupted stage that already made some trusted files read-only.
find /work/project -type d -exec chmod 0755 {} +
find /work/project -type f -exec chmod 0644 {} +
cp /opt/project/build.gradle /opt/project/settings.gradle /opt/project/gradle.lockfile /opt/project/gradle.properties /work/project/
cp /opt/project/gradle/verification-metadata.xml /work/project/gradle/
chmod 0444 /work/project/*.gradle /work/project/gradle.lockfile /work/project/gradle.properties /work/project/gradle/verification-metadata.xml
chmod 0555 /work/project/gradle
# Gradle requires a writable project directory; sticky root ownership protects trusted files.
chmod 1777 /work/project
umask 022
: >/control/ready.next
chmod 0444 /control/ready.next
mv /control/ready.next /control/ready
