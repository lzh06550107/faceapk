package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceFeatureCachePolicyTest {
    @Test
    public void exactMetadataMatchIsReusable() {
        assertTrue(FaceFeatureCachePolicy.isReusable(12, "ABC", 12, "abc", 1, 1, new byte[512]));
    }

    @Test
    public void faceVersionMismatchInvalidatesCache() {
        assertFalse(FaceFeatureCachePolicy.isReusable(12, "abc", 13, "abc", 1, 1, new byte[512]));
    }

    @Test
    public void shaMismatchInvalidatesCache() {
        assertFalse(FaceFeatureCachePolicy.isReusable(12, "abc", 12, "def", 1, 1, new byte[512]));
    }

    @Test
    public void schemaMismatchInvalidatesCache() {
        assertFalse(FaceFeatureCachePolicy.isReusable(12, "abc", 12, "abc", 1, 2, new byte[512]));
    }

    @Test
    public void invalidFeatureLengthIsNotReusable() {
        assertFalse(FaceFeatureCachePolicy.isReusable(12, "abc", 12, "abc", 1, 1, new byte[16]));
    }
}
