package com.punch.app.activity;

import android.content.Intent;

public class UiTestKioskHomeHostActivity extends KioskHomeActivity {
    private Intent startedIntent;
    private boolean finishRequested;
    private boolean interceptFinish = true;

    @Override
    public void startActivity(Intent intent) {
        startedIntent = intent;
    }

    @Override
    public void finish() {
        if (!interceptFinish) {
            super.finish();
            return;
        }
        finishRequested = true;
    }

    Intent getStartedIntent() {
        return startedIntent;
    }

    boolean wasFinishRequested() {
        return finishRequested;
    }

    void allowFinish() {
        interceptFinish = false;
    }
}
