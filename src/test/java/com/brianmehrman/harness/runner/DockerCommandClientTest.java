package com.brianmehrman.harness.runner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DockerCommandClientTest {
 @Test void outputOverflowIsTerminalRatherThanATransportFailure() {
  assertThatIllegalArgumentException().isThrownBy(() -> DockerCommandClient.bounded(new ByteArrayInputStream(new byte[1025]),1024));
 }

 @Test void archiveContainsOnlyRegularCandidateFilesWithUnicodeAndLongPaths() throws Exception {
  String path="src/main/java/"+"long/".repeat(40)+"例.java";
  byte[] bytes=DockerCommandClient.archive(Map.of("build.gradle","protected",path,"class Example {}","README.md","readme"));
  try(var tar=new TarArchiveInputStream(new ByteArrayInputStream(bytes),"UTF-8")) {
   var first=tar.getNextEntry();assertThat(first.getName()).isEqualTo("README.md");assertThat(first.isFile()).isTrue();
   var next=tar.getNextEntry();assertThat(next.getName()).isEqualTo(path);assertThat(next.isFile()).isTrue();
   assertThat(new String(tar.readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("class Example {}");assertThat(tar.getNextEntry()).isNull();
  }
  assertThatIllegalArgumentException().isThrownBy(() -> DockerCommandClient.archive(Map.of("src/main/java/../../evil.java","bad")));
 }
 @Test void rejectsSpoofedMultilineAndOversizeEnvelopes() {
  String good="HARNESS_RESULT_V1\tYQ==\tfalse\t\t\t\n";
  assertThat(DockerCommandClient.decode(good.getBytes()).logs()).containsExactly((byte)'a');
  for(String invalid:List.of(good+good,good.replace("false","maybe"),"success\n",good.replace("YQ==",Base64.getEncoder().encodeToString(new byte[262145]))))
   assertThatIllegalArgumentException().isThrownBy(() -> DockerCommandClient.decode(invalid.getBytes()));
 }
}
