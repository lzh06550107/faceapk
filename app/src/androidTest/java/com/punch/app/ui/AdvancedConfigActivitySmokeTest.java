package com.punch.app.ui;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;

import com.punch.app.activity.UiTestAdvancedConfigHostActivity;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
@Ignore("Device-owner status bar and foreground policy make this page unstable on the current kiosk device; keep it out of smoke automation.")
public class AdvancedConfigActivitySmokeTest extends BaseUiSmokeTest {

    @Test
    public void advancedConfig_shouldRenderKeyControls() throws Exception {
        startActivity(UiTestAdvancedConfigHostActivity.class, "et_base_url", 5_000L, false);

        UiObject2 baseUrlInput = device.wait(
                androidx.test.uiautomator.Until.findObject(By.res(context.getPackageName(), "et_base_url")),
                2_000L
        );
        assertNotNull("Missing et_base_url", baseUrlInput);

        UiObject2 companyIdInput = device.wait(
                androidx.test.uiautomator.Until.findObject(By.res(context.getPackageName(), "et_company_id")),
                2_000L
        );
        assertNotNull("Missing et_company_id", companyIdInput);

        UiObject2 copyFingerprintButton = device.wait(
                androidx.test.uiautomator.Until.findObject(By.res(context.getPackageName(), "btn_copy_fingerprint")),
                2_000L
        );
        assertNotNull("Missing btn_copy_fingerprint", copyFingerprintButton);
    }
}
