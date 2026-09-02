package com.punch.app.activity;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.punch.app.ui.BaseUiSmokeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LoginActivityExecutorLifecycleTest extends BaseUiSmokeTest {

    @Test
    public void destroyingActivityShutsDownLoginExecutor() {
        AtomicReference<LoginActivity> activityRef = new AtomicReference<>();
        ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class);
        scenario.onActivity(activityRef::set);

        scenario.close();

        assertTrue(activityRef.get().isLoginExecutorShutdownForTest());
    }
}
