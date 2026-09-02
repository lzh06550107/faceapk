package com.punch.app.ui;

import static org.junit.Assert.assertEquals;

import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.ToggleButton;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.punch.app.R;
import com.punch.app.activity.UiTestRecordsHostActivity;
import com.punch.app.fragment.RecordsFragment;
import com.punch.app.utils.SessionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ClockStatisticsOptionsTest extends BaseUiSmokeTest {
    private List<String> originalTimeRanges;

    @Before
    public void saveTimeRanges() {
        originalTimeRanges = new ArrayList<>(SessionManager.get().getCurrentTeamTimeRanges());
        SessionManager.get().saveCurrentTeamTimeRanges(Arrays.asList(
                "06:00-10:00",
                "10:00-14:00",
                "14:00-18:00",
                "18:00-22:00",
                "22:00-02:00"
        ));
    }

    @After
    public void restoreTimeRanges() {
        SessionManager.get().saveCurrentTeamTimeRanges(originalTimeRanges);
    }

    @Test
    public void onlineStatisticsKeepsThreeRegularRangesAndAppendsFixedOvertimeOptions() {
        try (ActivityScenario<UiTestRecordsHostActivity> scenario =
                     ActivityScenario.launch(UiTestRecordsHostActivity.class)) {
            scenario.onActivity(activity -> {
                ToggleButton toggle = activity.findViewById(R.id.toggle_mode);
                toggle.setChecked(true);

                Spinner spinner = activity.findViewById(R.id.spinner_punch_index);
                SpinnerAdapter adapter = spinner.getAdapter();
                assertEquals(8, adapter.getCount());
                assertEquals("06:00-10:00 \u4e0a\u73ed", adapter.getItem(0).toString());
                assertEquals("14:00-18:00 \u4e0b\u73ed", adapter.getItem(5).toString());
                assertEquals("\u52a0\u73ed\u4e0a\u73ed", adapter.getItem(6).toString());
                assertEquals("\u52a0\u73ed\u4e0b\u73ed", adapter.getItem(7).toString());

                assertClockIndex(activity.getRecordsFragment(), 6, 7);
                assertClockIndex(activity.getRecordsFragment(), 7, 8);
            });
        }
    }

    @Test
    public void offlineStatisticsKeepsFreePunchAndEveryConfiguredRange() {
        try (ActivityScenario<UiTestRecordsHostActivity> scenario =
                     ActivityScenario.launch(UiTestRecordsHostActivity.class)) {
            scenario.onActivity(activity -> {
                ToggleButton toggle = activity.findViewById(R.id.toggle_mode);
                toggle.setChecked(false);

                Spinner spinner = activity.findViewById(R.id.spinner_punch_index);
                SpinnerAdapter adapter = spinner.getAdapter();
                assertEquals(11, adapter.getCount());
                assertEquals("\u81ea\u7531\u6253\u5361", adapter.getItem(0).toString());
                assertEquals("22:00-02:00 \u4e0a\u73ed", adapter.getItem(9).toString());
                assertEquals("22:00-02:00 \u4e0b\u73ed", adapter.getItem(10).toString());
            });
        }
    }

    private static void assertClockIndex(RecordsFragment fragment,
                                         int position,
                                         int expectedClockIndex) {
        try {
            Field optionsField = RecordsFragment.class.getDeclaredField("punchOptions");
            optionsField.setAccessible(true);
            List<?> options = (List<?>) optionsField.get(fragment);
            Object option = options.get(position);
            Field clockIndexField = option.getClass().getDeclaredField("clockIndex");
            clockIndexField.setAccessible(true);
            assertEquals(expectedClockIndex, clockIndexField.getInt(option));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read the statistics clock index", e);
        }
    }
}
