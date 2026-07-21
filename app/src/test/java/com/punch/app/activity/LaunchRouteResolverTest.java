package com.punch.app.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LaunchRouteResolverTest {

    @Test
    public void hasHomeCategory_shouldReturnFalseWhenCategoriesAreNull() {
        assertFalse(LaunchRouteResolver.hasHomeCategory(null));
    }

    @Test
    public void hasHomeCategory_shouldReturnTrueWhenHomeCategoryPresent() {
        Set<String> categories = new HashSet<>();
        categories.add("android.intent.category.HOME");

        assertTrue(LaunchRouteResolver.hasHomeCategory(categories));
    }

    @Test
    public void hasHomeCategory_shouldReturnFalseWhenHomeCategoryMissing() {
        assertFalse(LaunchRouteResolver.hasHomeCategory(Collections.singleton("android.intent.category.DEFAULT")));
    }

    @Test
    public void resolveAuthenticatedEntry_shouldReturnMainActivityWhenTokenValid() {
        assertEquals(MainActivity.class, LaunchRouteResolver.resolveAuthenticatedEntry(true));
    }

    @Test
    public void resolveAuthenticatedEntry_shouldReturnLoginActivityWhenTokenInvalid() {
        assertEquals(LoginActivity.class, LaunchRouteResolver.resolveAuthenticatedEntry(false));
    }
}
