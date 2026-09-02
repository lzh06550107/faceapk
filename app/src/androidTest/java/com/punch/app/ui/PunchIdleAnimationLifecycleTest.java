package com.punch.app.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.punch.app.R;
import com.punch.app.activity.MainActivity;
import com.punch.app.widget.FaceFrameView;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PunchIdleAnimationLifecycleTest extends BaseUiSmokeTest {

    @Test
    public void scanAnimationRunsOnlyWhilePunchIsEnabled() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                FaceFrameView frameView = activity.findViewById(R.id.face_frame_view);

                assertFalse(frameView.isScanAnimationRunning());
                activity.findViewById(R.id.btn_punch_toggle).performClick();
                assertTrue(frameView.isScanAnimationRunning());
                activity.findViewById(R.id.btn_punch_toggle).performClick();
                assertFalse(frameView.isScanAnimationRunning());
            });
        }
    }
}
