package com.brianmehrman.harness.model;

public interface ProfileRevisionStore {
    ProfileRevision save(ProfileRevision profile);
    ProfileRevision get(String id);
}
