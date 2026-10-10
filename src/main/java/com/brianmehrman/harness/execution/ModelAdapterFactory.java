package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelAdapter;
import com.brianmehrman.harness.model.ProfileRevision;

@FunctionalInterface
public interface ModelAdapterFactory {
    ModelAdapter forProfile(ProfileRevision profile);
}
