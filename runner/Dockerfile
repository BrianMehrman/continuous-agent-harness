ARG JAVA_IMAGE=eclipse-temurin:21-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d
FROM ${JAVA_IMAGE}
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
