package com.brianmehrman.harness.runner;
/** Optional observer; fault injection implementations live exclusively in test sources. */
public interface RunnerBoundary { void reached(String boundary,String invocationId); }
