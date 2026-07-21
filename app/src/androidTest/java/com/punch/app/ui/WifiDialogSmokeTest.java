package com.punch.app.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.hamcrest.Matchers.endsWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.punch.app.R;
import com.punch.app.activity.LoginActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WifiDialogSmokeTest extends BaseUiSmokeTest {

    @Test
    public void configureWifi_shouldOpenDialogAndShowCoreFields() throws Exception {
        startActivity(LoginActivity.class, "btn_configure_wifi");
        onView(withId(R.id.btn_configure_wifi)).perform(click());

        onView(withHint("SSID")).check(matches(isDisplayed()));
        onView(withClassName(endsWith("AutoCompleteTextView"))).check(matches(isDisplayed()));
        onView(withId(android.R.id.button1)).check(matches(isDisplayed()));
        onView(withId(android.R.id.button2)).check(matches(isDisplayed()));
        onView(withId(android.R.id.button3)).check(matches(isDisplayed()));
    }
}
