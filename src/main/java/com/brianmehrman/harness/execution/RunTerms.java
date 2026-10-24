package com.brianmehrman.harness.execution;

import java.util.List;

public record RunTerms(String roleName, String roleDigest, String roleBlobId, List<String> tools,
        String modelName, boolean allowRemote, CostTerms cost) {
    public RunTerms { tools = List.copyOf(tools); }
}
