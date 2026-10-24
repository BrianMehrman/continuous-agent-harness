ARG JAVA_IMAGE=eclipse-temurin:21.0.12.1_1-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d
FROM ${JAVA_IMAGE}
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) archive_arch=x64; archive_sha=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c ;; \
      arm64) archive_arch=aarch64; archive_sha=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578 ;; \
      *) echo "Unsupported Java architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    archive="OpenJDK21U-jdk_${archive_arch}_linux_hotspot_21.0.13_9.tar.gz"; \
    curl --fail --location --retry 3 "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/${archive}" -o /tmp/temurin-jdk.tar.gz; \
    echo "${archive_sha}  /tmp/temurin-jdk.tar.gz" | sha256sum --check -; \
    mkdir /opt/java/patched; \
    tar -xzf /tmp/temurin-jdk.tar.gz -C /opt/java/patched --strip-components=1; \
    rm -rf /opt/java/openjdk /tmp/temurin-jdk.tar.gz; \
    mv /opt/java/patched /opt/java/openjdk; \
    /opt/java/openjdk/bin/java -version
ARG GRADLE_SHA256
RUN command -v flock && command -v curl && command -v tar
RUN curl --fail --location --retry 3 https://services.gradle.org/distributions/gradle-9.8.0-bin.zip -o /tmp/gradle.zip \
 && echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum --check - \
 && cd /opt && jar xf /tmp/gradle.zip && mv gradle-9.8.0 gradle && chmod +x /opt/gradle/bin/gradle && rm /tmp/gradle.zip
COPY starter/build.gradle /opt/project/trusted.gradle
COPY starter/settings.gradle starter/gradle.lockfile /opt/project/
COPY starter/gradle/verification-metadata.xml /opt/project/gradle/verification-metadata.xml
COPY runner/build.gradle /opt/runner/overrides.gradle
COPY runner/gradle.properties /opt/project/
RUN cat /opt/project/trusted.gradle /opt/runner/overrides.gradle > /opt/project/build.gradle
COPY runner/CommandMain.java runner/stage.sh runner/commands.json /opt/runner/
RUN javac --release 21 -d /opt/runner /opt/runner/CommandMain.java \
 && mkdir -p /work/input/src/main/java /work/input/src/test/java \
 && echo 'public class Warm {}' >/work/input/src/main/java/Warm.java \
 && echo 'import org.junit.jupiter.api.Test; class WarmTest { @Test void warm() {} }' >/work/input/src/test/java/WarmTest.java \
 && GRADLE_USER_HOME=/opt/gradle-home /opt/gradle/bin/gradle --no-daemon --console=plain --max-workers=1 jar test --project-cache-dir /work/cache -p /opt/project \
 && rm -rf /work /opt/gradle-home/daemon /opt/gradle-home/notifications \
 && mkdir /work /control && chmod -R a+rX /opt/project /opt/runner /opt/gradle-home
ENV JAVA_HOME=/opt/java/openjdk
USER 1000:1000
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp", "/opt/runner", "CommandMain"]
