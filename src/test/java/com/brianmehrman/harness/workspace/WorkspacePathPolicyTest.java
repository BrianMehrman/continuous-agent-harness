package com.brianmehrman.harness.workspace;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class WorkspacePathPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"../README.md", "/README.md", "src/main/java/../Bad.java", "src/main/java//Bad.java",
            "src/main/java/./Bad.java", "src/main/java/Bad.java/", "src/main/java/a\\Bad.java", "C:/Bad.java",
            "build.gradle", "settings.gradle", "REQUIREMENTS.md", "gradlew", "src/main/resources/app.yaml", "README.md\n"})
    void rejectsUnsafeOrProtectedPaths(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateWritePath(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"README.md", "src/main/java/Tracker.java", "src/main/java/example/Tracker.java", "src/test/java/example/TrackerTest.java"})
    void acceptsAllowedPaths(String path) {
        assertThatCode(() -> WorkspacePathPolicy.validateWritePath(path)).doesNotThrowAnyException();
    }

    @Test void countsUtf8BytesAndRejectsOversizeFiles() {
        assertThat(WorkspacePathPolicy.validateSnapshot(Map.of("README.md", "é"))).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(Map.of("README.md", "é".repeat(32769))));
        assertThatCode(() -> WorkspacePathPolicy.validateSnapshot(Map.of("README.md", "a".repeat(65536)))).doesNotThrowAnyException();
    }

    @Test void rejectsFileCountAndTotalByteOverflow() {
        var files = new HashMap<String,String>();
        for (int i=0;i<64;i++) files.put("src/main/java/F"+i+".java", "");
        assertThatCode(() -> WorkspacePathPolicy.validateSnapshot(files)).doesNotThrowAnyException();
        files.put("README.md", "");
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(files));
        files.clear();
        for (int i=0;i<16;i++) files.put("src/main/java/F"+i+".java", "a".repeat(65536));
        assertThat(WorkspacePathPolicy.validateSnapshot(files)).isEqualTo(1048576);
        files.put("README.md", "x");
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(files));
    }

    @Test void rejectsFileDirectoryCollisions() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(
                Map.of("src/main/java/Foo.java", "", "src/main/java/Foo.java/Bar.java", "")));
    }

    @Test void rejectsInvalidTextAndNulls() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(Map.of("README.md", String.valueOf((char)0xD800))));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateSnapshot(Map.of("README.md", String.valueOf((char)0))));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.validateWritePath(null));
    }

    @Test void filesystemBoundaryRejectsSymlinksDirectoriesAndEscapes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var file = java.nio.file.Files.writeString(root.resolve("File.java"), "class File {}");
        assertThatCode(() -> WorkspacePathPolicy.requireRegularFile(root,file)).doesNotThrowAnyException();
        var link = java.nio.file.Files.createSymbolicLink(root.resolve("Link.java"),file);
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.requireRegularFile(root,link));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.requireRegularFile(root,root));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.requireRegularFile(root,root.resolve("../outside")));
        var directory = java.nio.file.Files.createDirectory(root.resolve("nested"));
        var nested = java.nio.file.Files.writeString(directory.resolve("Nested.java"), "");
        var directoryLink = java.nio.file.Files.createSymbolicLink(root.resolve("alias"),directory);
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePathPolicy.requireRegularFile(root,directoryLink.resolve(nested.getFileName())));
    }

    @Test void hashesAreOrderedFramedAndContentSensitive() {
        assertThat(SnapshotHasher.content("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SnapshotHasher.snapshot(Map.of("b","2","a","1"))).isEqualTo(SnapshotHasher.snapshot(Map.of("a","1","b","2")));
        assertThat(SnapshotHasher.snapshot(Map.of("a","bc"))).isNotEqualTo(SnapshotHasher.snapshot(Map.of("ab","c")));
        assertThat(SnapshotHasher.snapshot(Map.of("README.md","a"))).isNotEqualTo(SnapshotHasher.snapshot(Map.of("README.md","b")));
    }
}
