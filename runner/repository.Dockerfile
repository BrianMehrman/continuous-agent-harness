ARG BENCHMARK_IMAGE
FROM ${BENCHMARK_IMAGE}
USER 0:0
COPY repository/build.gradle /opt/project/build.gradle
COPY repository/settings.gradle repository/gradle.lockfile repository/gradle.properties /opt/project/
COPY repository/gradle/verification-metadata.xml /opt/project/gradle/verification-metadata.xml
RUN mkdir -p /work/input/src/main/java /work/input/src/test/java \
 && printf 'import org.springframework.boot.SpringApplication; import io.temporal.client.WorkflowClient; import tools.jackson.databind.json.JsonMapper; public class Warm { SpringApplication spring; WorkflowClient temporal; JsonMapper json; }\n' >/work/input/src/main/java/Warm.java \
 && printf 'import org.junit.jupiter.api.Test; class WarmTest { @Test void warm() {} }\n' >/work/input/src/test/java/WarmTest.java \
 && GRADLE_USER_HOME=/opt/gradle-home /opt/gradle/bin/gradle --no-daemon --console=plain --max-workers=1 jar test --project-cache-dir /work/cache -p /opt/project \
 && GRADLE_USER_HOME=/opt/gradle-home /opt/gradle/bin/gradle --offline --no-daemon --console=plain --max-workers=1 --rerun-tasks jar test --project-cache-dir /work/cache -p /opt/project \
 && rm -rf /work /opt/gradle-home/daemon /opt/gradle-home/notifications \
 && mkdir /work && chmod -R a+rX /opt/project /opt/gradle-home
USER 1000:1000
