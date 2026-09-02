package com.punch.app.ui;

import static org.junit.Assert.assertEquals;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.punch.app.activity.MainActivity;
import com.punch.app.fragment.ConfigFragment;
import com.punch.app.fragment.PunchFragment;
import com.punch.app.fragment.RecordsFragment;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class MainActivityFragmentRecreationTest extends BaseUiSmokeTest {

    @Test
    public void recreate_shouldKeepOneFragmentInstancePerMainTab() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.recreate();
            scenario.recreate();
            scenario.recreate();

            scenario.onActivity(activity -> {
                List<Fragment> fragments = activity.getSupportFragmentManager().getFragments();
                assertEquals(1, countFragments(fragments, PunchFragment.class));
                assertEquals(0, countFragments(fragments, RecordsFragment.class));
                assertEquals(0, countFragments(fragments, ConfigFragment.class));
            });
        }
    }

    private static int countFragments(List<Fragment> fragments, Class<?> fragmentClass) {
        int count = 0;
        for (Fragment fragment : fragments) {
            if (fragmentClass.isInstance(fragment)) {
                count += 1;
            }
        }
        return count;
    }
}
