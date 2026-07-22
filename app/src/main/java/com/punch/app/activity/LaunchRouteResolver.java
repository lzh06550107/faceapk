package com.punch.app.activity;

import android.content.Intent;

import java.util.Set;

final class LaunchRouteResolver {
    private LaunchRouteResolver() {
    }

    static boolean shouldRouteImmediately(Intent intent) {
        return intent != null && hasHomeCategory(intent.getCategories());
    }

    static boolean hasHomeCategory(Set<String> categories) {
        return categories != null && categories.contains(Intent.CATEGORY_HOME);
    }

    static Class<?> resolveAuthenticatedEntry(boolean tokenValid) {
        return tokenValid ? MainActivity.class : LoginActivity.class;
    }

    static Class<?> resolveNext(boolean tokenValid, boolean setupCompleted) {
        if (tokenValid) {
            return MainActivity.class;
        }
        return setupCompleted ? LoginActivity.class : SetupWizardActivity.class;
    }
}
