package com.xxywebsite.graalvm;

import org.graalvm.nativeimage.hosted.Feature;

public class NativeImageFeature implements Feature {
    public NativeImageFeature() {

    }
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        System.out.println("before native-image");
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        System.out.println("after native-image");
    }
}
