package com.punch.app.network;

import com.punch.app.PunchApplication;

/** Test-only application: suppresses production startup jobs before any network test command runs. */
public class NetworkFaultTestApplication extends PunchApplication {
    static {
        PunchApplication.setUiTestModeForTest(true);
    }
}
