package com.brianmehrman.harness.runs;

public interface BlobStore {
    String put(String mediaType, byte[] bytes);
    byte[] get(String digest);
}
