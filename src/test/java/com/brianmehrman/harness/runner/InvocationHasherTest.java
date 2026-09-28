package com.brianmehrman.harness.runner;
import com.brianmehrman.harness.workspace.SnapshotRef;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class InvocationHasherTest {
    private final SnapshotRef snapshot=new SnapshotRef("a".repeat(64));
    private final String image="sha256:"+"b".repeat(64);
    @Test void hashesEveryExecutionInputAndFramesIdentities() {
        var r=new InvocationRequest("ab","c",snapshot,Operation.BUILD,123);
        String hash=InvocationHasher.hash(r,image);
        assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(InvocationHasher.hash(r,image));
        assertThat(hash).isNotEqualTo(InvocationHasher.hash(new InvocationRequest("a","bc",snapshot,Operation.BUILD,123),image));
        assertThat(hash).isNotEqualTo(InvocationHasher.hash(new InvocationRequest("ab","c",snapshot,Operation.TEST,123),image));
        assertThat(hash).isNotEqualTo(InvocationHasher.hash(new InvocationRequest("ab","c",snapshot,Operation.BUILD,124),image));
        assertThat(hash).isNotEqualTo(InvocationHasher.hash(r,"sha256:"+"c".repeat(64)));
    }
    @Test void rejectsUnsupportedOperationsMutableImagesAndInvalidIdentity() {
        assertThatIllegalArgumentException().isThrownBy(() -> InvocationHasher.hash(new InvocationRequest("r","i",snapshot,Operation.EVALUATE,123),image));
        assertThatIllegalArgumentException().isThrownBy(() -> InvocationHasher.hash(new InvocationRequest("r","i",snapshot,Operation.BUILD,123),"runner:latest"));
        assertThatIllegalArgumentException().isThrownBy(() -> InvocationHasher.hash(new InvocationRequest("r\n","i",snapshot,Operation.BUILD,123),image));
    }
}
