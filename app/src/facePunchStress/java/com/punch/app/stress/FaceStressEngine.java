package com.punch.app.stress;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;

public final class FaceStressEngine {
    private FaceStressEngine() {}

    public static StressResult run(Context context, int count) {
        StressResult result = new StressResult("Face", count);
        FaceStressFixture fixture = FaceStressFixture.prepare(context);
        try {
            if (!fixture.isReady()) {
                result.error = fixture.error.isEmpty() ? "stress_fixture_not_ready" : fixture.error;
                return result;
            }

            Employee employee = fixture.employee;
            result.employeeId = employee.id;
            result.fixtureSource = fixture.fixtureSource;
            result.fixtureRegistered = fixture.fixtureRegistered;
            for (int i = 0; i < count; i++) {
                Bitmap bitmap = BitmapFactory.decodeFile(fixture.imagePath);
                if (bitmap == null) {
                    result.add(0, false, "decode_failed");
                    continue;
                }

                long started = System.nanoTime();
                FaceManager.RecognizeResult recognized;
                try {
                    recognized = FaceManager.get().recognizeFromBitmap(bitmap);
                } finally {
                    bitmap.recycle();
                }
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

                boolean ok = recognized != null && recognized.matched
                        && employee.id.equals(recognized.empId);
                String outcome;
                if (ok) outcome = "matched";
                else if (recognized == null) outcome = "null_result";
                else if (recognized.matched) outcome = "matched_other:" + recognized.empId;
                else outcome = recognized.errorMsg;
                result.add(elapsedMs, ok, outcome);
            }
            return result;
        } finally {
            fixture.close();
        }
    }
}
