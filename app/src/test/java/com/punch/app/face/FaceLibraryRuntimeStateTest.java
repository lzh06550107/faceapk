package com.punch.app.face;

import com.punch.app.model.Employee;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceLibraryRuntimeStateTest {
    @Test
    public void successfulBuildCanBeReusedForSameScopeAndEmployees() {
        FaceLibraryRuntimeState state = new FaceLibraryRuntimeState();
        String fingerprint = FaceLibraryRuntimeState.fingerprint(
                Collections.singletonList(employee("E001", 1)));

        assertFalse(state.canReuse("server-a|1|device-a", fingerprint));

        state.markReady("server-a|1|device-a", fingerprint);

        assertTrue(state.canReuse("server-a|1|device-a", fingerprint));
    }

    @Test
    public void serverScopeChangeRequiresRebuild() {
        FaceLibraryRuntimeState state = new FaceLibraryRuntimeState();
        String fingerprint = FaceLibraryRuntimeState.fingerprint(
                Collections.singletonList(employee("E001", 1)));
        state.markReady("server-a|1|device-a", fingerprint);

        assertFalse(state.canReuse("server-b|1|device-a", fingerprint));
    }

    @Test
    public void employeeFaceChangeRequiresRebuildRegardlessOfListOrder() {
        FaceLibraryRuntimeState state = new FaceLibraryRuntimeState();
        Employee first = employee("E001", 1);
        Employee second = employee("E002", 1);
        String original = FaceLibraryRuntimeState.fingerprint(Arrays.asList(first, second));
        state.markReady("server-a|1|device-a", original);

        assertTrue(state.canReuse(
                "server-a|1|device-a",
                FaceLibraryRuntimeState.fingerprint(Arrays.asList(second, first))));

        second.faceVersion = 2;
        assertFalse(state.canReuse(
                "server-a|1|device-a",
                FaceLibraryRuntimeState.fingerprint(Arrays.asList(first, second))));
    }

    @Test
    public void invalidatedLibraryCannotBeReused() {
        FaceLibraryRuntimeState state = new FaceLibraryRuntimeState();
        String fingerprint = FaceLibraryRuntimeState.fingerprint(Collections.emptyList());
        state.markReady("server-a|1|device-a", fingerprint);

        state.invalidate();

        assertFalse(state.canReuse("server-a|1|device-a", fingerprint));
    }

    private static Employee employee(String id, int faceVersion) {
        Employee employee = new Employee();
        employee.id = id;
        employee.faceImageUrl = "https://images.example/" + id + ".jpg";
        employee.faceImageSha256 = "sha-" + id;
        employee.faceVersion = faceVersion;
        employee.faceStatus = "enabled";
        employee.localFaceId = "local-" + id;
        employee.faceRegistered = 1;
        employee.updatedAt = 100L;
        return employee;
    }
}
