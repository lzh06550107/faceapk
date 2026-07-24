package com.punch.app.service;

import android.content.Context;

import com.punch.app.PunchApplication;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceManager;
import com.punch.app.face.FaceRegistrationManager;
import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.model.SyncQueueItem;
import com.punch.app.network.ApiResult;
import com.punch.app.network.InteractionLogger;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.network.dto.EmployeeSyncData;
import com.punch.app.network.dto.EventResultDto;
import com.punch.app.network.dto.HeartbeatDto;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SyncCoordinator {
    private static final String TAG = "SyncCoordinator";
    private static final long FACE_REGISTRATION_TIMEOUT_SECONDS = 300L;
    private static final String FAILURE_MSG_EMPLOYEE_SYNC_FAILED = "员工同步失败";
    private static final String FAILURE_MSG_FACE_SDK_NOT_READY = "人脸引擎未就绪";
    private static final String FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE = "人脸注册未完成";
    private static final String FAILURE_MSG_DEVICE_CONFIG_SYNC_FAILED = "设备配置同步失败";
    private static final String STATUS_MSG_PUNCH_READY = "准备完成，可以开始打卡";
    private static final String STATUS_MSG_PUNCH_PARTIAL_READY = "打卡已可用，部分员工人脸未入库";
    private static final String STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED = "人脸库重建失败，请稍后重试";
    private static SyncCoordinator instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean heartbeatQueued = new AtomicBoolean(false);

    private SyncCoordinator() {
    }

    public static synchronized SyncCoordinator get() {
        if (instance == null) {
            instance = new SyncCoordinator();
        }
        return instance;
    }

    public void enqueueHeartbeatCycle(Context context) {
        Context appContext = context.getApplicationContext();
        if (!heartbeatQueued.compareAndSet(false, true)) {
            AppLogger.d(TAG, "Heartbeat cycle already queued");
            return;
        }
        executor.execute(() -> {
            try {
                runHeartbeatCycle(appContext);
            } finally {
                heartbeatQueued.set(false);
            }
        });
    }

    public boolean syncEmployeesForPreparation(Context context) {
        return syncEmployeesInternal(context.getApplicationContext(), false).overallSuccess;
    }

    public boolean rebuildLocalFaceLibrary(Context context) {
        Context appContext = context.getApplicationContext();
        if (!FaceManager.get().isInitialized()) {
            AppLogger.w(TAG, "Cannot rebuild face library: face SDK is not ready");
            return false;
        }
        FaceRegistrationOutcome outcome = waitForFaceRegistration(appContext);
        PunchApplication app = PunchApplication.get();
        if (!rebuildFinalFaceLibrary(appContext, app)) {
            return false;
        }
        if (app != null) {
            publishPreparationOutcome(app, outcome);
        }
        return outcome.isUsable();
    }

    private void runHeartbeatCycle(Context appContext) {
        if (!SessionManager.get().isDeviceRegistered()) {
            AppLogger.d(TAG, "Skip sync: device not registered");
            return;
        }
        if (!SessionManager.get().isTokenValid()) {
            AppLogger.d(TAG, "Skip sync: token missing or expired");
            return;
        }

        syncPunches(appContext);

        ApiResult<HeartbeatDto.HeartbeatData> heartbeat = ApiService.fetchHeartbeat(appContext);
        if (!heartbeat.success || heartbeat.data == null) {
            AppLogger.w(TAG, "Heartbeat failed: " + heartbeat.message);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_HEARTBEAT,
                    "心跳请求失败",
                    safeString(heartbeat.message)
            );
            return;
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;
        SessionManager.get().saveLastHeartbeatTime(nowSeconds);
        if (heartbeat.data.serverTime > 0) {
            SessionManager.get().saveLastServerTime(heartbeat.data.serverTime);
        }

        if (heartbeat.data.hasChanges && !heartbeat.data.events.isEmpty()) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_HEARTBEAT,
                    "收到平台事件",
                    "事件数 " + heartbeat.data.events.size()
            );
            applyHeartbeatEvents(appContext, heartbeat.data.events);
        }
    }

    private void applyHeartbeatEvents(Context appContext, List<HeartbeatDto.HeartbeatEventData> events) {
        for (HeartbeatDto.HeartbeatEventData event : events) {
            if (event == null || isBlank(event.cursor)) {
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_HEARTBEAT,
                        "收到无效事件",
                        "缺少 cursor，事件已跳过"
                );
                continue;
            }

            InteractionLogger.logBusiness(
                    resolveEventGroup(event.eventType),
                    "开始处理平台事件",
                    "cursor=" + event.cursor + "\nevent_type=" + safeString(event.eventType)
            );
            EventProcessingOutcome outcome = handleEvent(appContext, event);

            ApiResult<Void> ackResult = ApiService.reportEventResult(
                    event.cursor,
                    event.eventType,
                    outcome.success,
                    outcome.employeeResults,
                    outcome.failureMessage
            );
            if (!ackResult.success) {
                AppLogger.w(TAG, "Event result report failed: " + ackResult.message + ", cursor=" + event.cursor);
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EVENT_RESULT,
                        "事件结果回传失败",
                        "cursor=" + event.cursor + "\nreason=" + safeString(ackResult.message)
                );
                break;
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_EVENT_RESULT,
                    "事件结果已回传",
                    "cursor=" + event.cursor + "\nsuccess=" + outcome.success
            );
            if (!outcome.success) {
                AppLogger.w(TAG, "Event handled with failure: cursor=" + event.cursor + ", reason=" + outcome.failureMessage);
                InteractionLogger.logBusinessFailure(
                        resolveEventGroup(event.eventType),
                        "平台事件处理失败",
                        "cursor=" + event.cursor + "\nreason=" + safeString(outcome.failureMessage)
                );
                break;
            }
        }
    }

    private EventProcessingOutcome handleEvent(Context context, HeartbeatDto.HeartbeatEventData event) {
        String eventType = safeString(event.eventType);
        switch (eventType) {
            case "person_changed":
                return syncEmployeesEvent(context);
            case "config_changed":
                return syncDeviceConfig(context)
                        ? EventProcessingOutcome.success()
                        : EventProcessingOutcome.failure(FAILURE_MSG_DEVICE_CONFIG_SYNC_FAILED);
            default:
                AppLogger.d(TAG, "Ignore heartbeat event: " + eventType);
                return EventProcessingOutcome.success();
        }
    }

    private EventProcessingOutcome syncEmployeesEvent(Context context) {
        EmployeeSyncProcessingResult result = syncEmployeesInternal(context, true);
        return result.eventSuccess
                ? EventProcessingOutcome.success(result.employeeResults)
                : EventProcessingOutcome.failure(result.failureMessage, result.employeeResults);
    }

    private void syncPunches(Context context) {
        DatabaseHelper db = DatabaseHelper.get(context);
        List<SyncQueueItem> queue = db.getSyncQueue(Constants.ACTION_PUNCH_PUSH);
        if (queue.isEmpty()) {
            return;
        }

        List<PunchRecord> unsyncedPunches = db.getUnsyncedPunchRecords();
        Map<String, PunchRecord> punchByClientId = new HashMap<>();
        for (PunchRecord punch : unsyncedPunches) {
            punchByClientId.put(punch.clientRecordId, punch);
        }

        for (SyncQueueItem item : queue) {
            if (item.retryCount >= Constants.SYNC_MAX_RETRY) {
                continue;
            }

            PunchRecord punch = punchByClientId.get(item.recordId);
            if (punch == null) {
                db.removeSyncQueueItem(item.id);
                continue;
            }

            ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);
            if (result.success) {
                db.markPunchSynced(punch.id);
                db.removeSyncQueueItem(item.id);
                AppLogger.i(TAG, "Punch synced: " + punch.clientRecordId);
            } else {
                db.incrementSyncRetry(item.id);
                AppLogger.w(TAG, "Punch sync failed: " + result.message);
            }
        }
    }

    private boolean syncDeviceConfig(Context context) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.reportStatusEvent("\u6536\u5230\u914d\u7f6e\u53d8\u66f4\uff0c\u6b63\u5728\u540c\u6b65\u8bbe\u5907\u914d\u7f6e...", PunchApplication.STATUS_LEVEL_PROGRESS);
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_DEVICE_CONFIG,
                "开始同步设备配置",
                "收到 config_changed 事件，准备拉取最新配置"
        );
        ApiResult<DeviceDto.DeviceConfigData> result = ApiService.fetchDeviceConfig();
        if (!result.success || result.data == null) {
            if (app != null) {
                app.reportStatusEvent("\u8bbe\u5907\u914d\u7f6e\u540c\u6b65\u5931\u8d25\uff0c\u7b49\u5f85\u91cd\u8bd5", PunchApplication.STATUS_LEVEL_ERROR);
            }
            AppLogger.w(TAG, "Device config sync failed: " + result.message);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_DEVICE_CONFIG,
                    "设备配置同步失败",
                    safeString(result.message)
            );
            return false;
        }

        applyDeviceConfig(result.data, true);
        if (app != null) {
            app.reportStatusEvent("\u8bbe\u5907\u914d\u7f6e\u5df2\u66f4\u65b0", PunchApplication.STATUS_LEVEL_SUCCESS);
        }
        if (FaceManager.get().isInitialized()) {
            FaceManager.get().refreshRuntimeConfig();
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_DEVICE_CONFIG,
                "设备配置同步完成",
                "本地配置已更新"
        );
        return true;
    }

    private void applyDeviceConfig(DeviceDto.DeviceConfigData data, boolean preserveLocalBindings) {
        SessionManager.get().saveDeviceConfigInitialized(true);
        SessionManager.get().saveLineBindingOptions(data.lines);
        SessionManager.get().saveTeamBindingOptions(data.teams);
        if (!preserveLocalBindings && (!isBlank(data.lineCode) || !isBlank(data.lineName))) {
            SessionManager.get().saveLineBinding(data.lineCode, data.lineName);
        }
        if (!preserveLocalBindings && data.teamBindingId > 0) {
            SessionManager.get().saveTeamBindingId(data.teamBindingId);
        }
        if (!preserveLocalBindings && !isBlank(data.teamBindingName)) {
            SessionManager.get().saveTeamBindingName(data.teamBindingName);
        }
        SessionManager.get().saveCurrentTeamTimeRanges(resolveTeamTimeRanges(
                data,
                preserveLocalBindings ? SessionManager.get().getTeamBindingId() : data.teamBindingId
        ));
        SessionManager.get().saveCheckCount(data.checkCount);
        if (!isBlank(data.account)) {
            SessionManager.get().saveAccount(data.account);
        }
        if (!isBlank(data.password)) {
            SessionManager.get().savePassword(data.password);
        }
        SessionManager.get().saveUpdateInfo(
                data.updateInfo.needUpdate || data.needUpdate,
                data.updateInfo.apkUrl,
                data.updateInfo.currentVersion,
                data.updateInfo.targetVersion,
                data.updateInfo.versionName
        );
        if (data.matchThreshold != null) {
            SessionManager.get().saveMatchThreshold(data.matchThreshold);
        }
        if (data.faceThreshold != null) {
            SessionManager.get().saveFaceThreshold(data.faceThreshold);
        }
        if (data.livenessCheck != null) {
            SessionManager.get().saveLivenessCheck(data.livenessCheck);
        }
        if (data.maskDetect != null) {
            SessionManager.get().saveMaskDetectEnabled(data.maskDetect);
        }
        if (data.timeoutSeconds != null) {
            SessionManager.get().saveRecognitionTimeoutSeconds(data.timeoutSeconds);
        }
        if (!isBlank(data.recognitionDistanceMode)) {
            SessionManager.get().saveRecognitionDistanceMode(data.recognitionDistanceMode);
        }
    }

    private EmployeeSyncProcessingResult syncEmployeesInternal(Context context, boolean collectEventResults) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.beginPunchDataPreparation("正在同步员工数据...");
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "开始同步员工列表",
                collectEventResults ? "事件模式：需要回传逐员工结果" : "准备模式：仅构建本地人脸数据"
        );
        DatabaseHelper db = DatabaseHelper.get(context);
        int page = 1;
        LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults =
                collectEventResults ? new LinkedHashMap<>() : null;
        LinkedHashMap<String, Employee> registrationTargets =
                collectEventResults ? new LinkedHashMap<>() : null;

        while (true) {
            ApiResult<EmployeeSyncData> result = ApiService.syncEmployees(page);
            if (!result.success || result.data == null) {
                if (app != null) {
                    app.markPunchRecognitionFailed("\u5458\u5de5\u540c\u6b65\u5931\u8d25\uff0c\u7b49\u5f85\u91cd\u8bd5");
                }
                AppLogger.w(TAG, "Employee sync failed: " + result.message);
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工同步失败",
                        "page=" + page + "\nreason=" + safeString(result.message)
                );
                return EmployeeSyncProcessingResult.failure(
                        FAILURE_MSG_EMPLOYEE_SYNC_FAILED,
                        toEmployeeResultList(employeeResults)
                );
            }

            EmployeeSyncData data = result.data;
            for (EmployeeSyncData.ChangeItem changeItem : data.changeItems) {
                if (changeItem == null || isBlank(changeItem.numbers)) {
                    continue;
                }
                if ("delete".equalsIgnoreCase(changeItem.opType)) {
                    if (!applyDeleteChange(db, changeItem)) {
                        if (collectEventResults) {
                            employeeResults.put(
                                    changeItem.numbers,
                                    EventResultDto.EmployeeResult.success(changeItem.numbers, "delete")
                            );
                        }
                        continue;
                    }
                    if (collectEventResults) {
                        employeeResults.put(
                                changeItem.numbers,
                                EventResultDto.EmployeeResult.success(changeItem.numbers, "delete")
                        );
                    }
                    continue;
                }

                Employee incoming = changeItem.employee;
                if (incoming == null) {
                    if (collectEventResults) {
                        employeeResults.put(
                                changeItem.numbers,
                                EventResultDto.EmployeeResult.failure(
                                        changeItem.numbers,
                                        safeOpType(changeItem.opType),
                                        FaceManager.ERROR_INVALID_FACE_IMAGE
                                )
                        );
                    }
                    continue;
                }

                Employee existing = db.getEmployee(incoming.id);
                if (isStaleEmployeeChange(existing, changeItem.opTime)) {
                    if (collectEventResults) {
                        employeeResults.put(
                                changeItem.numbers,
                                EventResultDto.EmployeeResult.success(
                                        changeItem.numbers,
                                        safeOpType(changeItem.opType)
                                )
                        );
                    }
                    continue;
                }

                Employee merged = mergeEmployee(existing, incoming);
                db.upsertEmployee(merged);
                if (collectEventResults) {
                    EventResultDto.EmployeeResult employeeResult =
                            EventResultDto.EmployeeResult.success(changeItem.numbers, safeOpType(changeItem.opType));
                    if (isBlank(merged.faceImageUrl)) {
                        employeeResult.success = false;
                        employeeResult.failMsg = FaceManager.ERROR_INVALID_FACE_IMAGE;
                    } else if (merged.faceRegistered != 1) {
                        registrationTargets.put(merged.id, merged);
                    }
                    employeeResults.put(changeItem.numbers, employeeResult);
                }
            }
            if (!data.hasMore) {
                if (data.serverTime > 0) {
                    SessionManager.get().saveLastServerTime(data.serverTime);
                }
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工列表拉取完成",
                        "最后页 page=" + page + "\n变更数 " + data.changeItems.size()
                );
                break;
            }
            page += 1;
        }

        if (!FaceManager.get().isInitialized()) {
            if (app != null) {
                app.markPunchRecognitionFailed("\u4eba\u8138\u5f15\u64ce\u672a\u5c31\u7eea\uff0c\u65e0\u6cd5\u91cd\u5efa\u4eba\u8138\u5e93");
            }
            AppLogger.w(TAG, "Employee sync applied but face SDK is not ready");
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "员工数据已入库，但人脸引擎未就绪",
                    FAILURE_MSG_FACE_SDK_NOT_READY
            );
            markPendingRegistrationsFailed(
                    employeeResults,
                    registrationTargets,
                    FAILURE_MSG_FACE_SDK_NOT_READY
            );
            return EmployeeSyncProcessingResult.failure(
                    FAILURE_MSG_FACE_SDK_NOT_READY,
                    toEmployeeResultList(employeeResults)
            );
        }

        if (app != null) {
            app.updatePunchDataPreparationStatus("\u6b63\u5728\u4e0b\u8f7d\u5e76\u6821\u9a8c\u4eba\u8138\u56fe\u7247...");
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "开始下载并校验人脸图片",
                collectEventResults ? "仅处理本次事件涉及的人员" : "处理当前全部待注册人员"
        );
        if (collectEventResults) {
            FaceRegistrationOutcome registrationOutcome = waitForFaceRegistration(
                    context,
                    new ArrayList<>(registrationTargets.values())
            );
            List<EventResultDto.EmployeeResult> finalResults =
                    applyRegistrationResults(employeeResults, registrationOutcome.results);
            if (!registrationOutcome.completed) {
                markMissingRegistrationResultsFailed(
                        employeeResults,
                        registrationTargets,
                        registrationOutcome.results,
                        FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE
                );
                if (app != null) {
                    app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
                }
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "人脸注册未完整完成",
                        FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE
                );
                return EmployeeSyncProcessingResult.failure(
                        FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE,
                        toEmployeeResultList(employeeResults)
                );
            }
            if (!rebuildFinalFaceLibrary(context, app)) {
                return EmployeeSyncProcessingResult.failure(
                        STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED,
                        toEmployeeResultList(employeeResults)
                );
            }
            if (app != null) {
                publishPreparationOutcome(app, registrationOutcome);
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "员工事件处理完成",
                    "成功 " + registrationOutcome.succeeded + "\n失败 " + registrationOutcome.failed
            );
            return EmployeeSyncProcessingResult.success(true, finalResults);
        }

        FaceRegistrationOutcome registrationOutcome = waitForFaceRegistration(context);
        if (!rebuildFinalFaceLibrary(context, app)) {
            return EmployeeSyncProcessingResult.failure(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED, new ArrayList<>());
        }
        boolean ready = registrationOutcome.isUsable();
        if (app != null) {
            publishPreparationOutcome(app, registrationOutcome);
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "准备模式员工同步完成",
                "可用状态 " + ready + "\n成功 " + registrationOutcome.succeeded + "\n失败 " + registrationOutcome.failed
        );
        return EmployeeSyncProcessingResult.success(ready, new ArrayList<>());
    }

    private Employee mergeEmployee(Employee existing, Employee incoming) {
        if (existing == null) {
            incoming.faceStatus = isBlank(incoming.faceStatus) ? "enabled" : incoming.faceStatus;
            incoming.status = isBlank(incoming.status) ? Constants.STATUS_NORMAL : incoming.status;
            incoming.isDeleted = 0;
            return incoming;
        }

        boolean faceChanged = !safeString(existing.faceImageUrl).equals(safeString(incoming.faceImageUrl));
        incoming.name = isBlank(incoming.name) ? existing.name : incoming.name;
        incoming.dept = isBlank(incoming.dept) ? existing.dept : incoming.dept;
        incoming.faceImageSha256 = existing.faceImageSha256;
        incoming.faceVersion = existing.faceVersion;
        incoming.faceStatus = isBlank(incoming.faceStatus) ? safeString(existing.faceStatus) : incoming.faceStatus;
        incoming.localFaceId = faceChanged ? "" : existing.localFaceId;
        incoming.faceRegistered = faceChanged ? 0 : existing.faceRegistered;
        incoming.assignedLineCode = existing.assignedLineCode;
        incoming.assignedLineName = existing.assignedLineName;
        incoming.status = isBlank(incoming.status) ? safeString(existing.status) : incoming.status;
        incoming.syncVersion = existing.syncVersion;
        incoming.isDeleted = 0;
        if (incoming.updatedAt <= 0) {
            incoming.updatedAt = existing.updatedAt;
        }
        return incoming;
    }

    private boolean applyDeleteChange(DatabaseHelper db, EmployeeSyncData.ChangeItem changeItem) {
        Employee existing = db.getEmployee(changeItem.numbers);
        if (isStaleEmployeeChange(existing, changeItem.opTime)) {
            return false;
        }
        if (existing == null) {
            return false;
        }
        db.markEmployeeDeleted(changeItem.numbers, changeItem.opTime);
        return true;
    }

    private FaceRegistrationOutcome waitForFaceRegistration(Context context) {
        return waitForFaceRegistration(context, null);
    }

    private boolean rebuildFinalFaceLibrary(Context context, PunchApplication app) {
        if (app != null) {
            app.updatePunchDataPreparationStatus("\u6b63\u5728\u91cd\u5efa\u4eba\u8138\u5e93...");
        }
        boolean success = FaceManager.get().rebuildFaceLibrarySync(context);
        if (!success) {
            if (app != null) {
                app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            }
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "人脸库最终重建失败",
                    STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED
            );
        }
        return success;
    }

    private FaceRegistrationOutcome waitForFaceRegistration(Context context, List<Employee> employees) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<FaceRegistrationManager.RegistrationResult>> holder =
                new AtomicReference<>(new ArrayList<>());

        FaceRegistrationManager.DetailedCallback callback = results -> {
            holder.set(results == null ? new ArrayList<>() : new ArrayList<>(results));
            latch.countDown();
        };
        if (employees == null) {
            FaceRegistrationManager.get().validateEmployeesForRebuild(
                    context,
                    DatabaseHelper.get(context).getUnregisteredFaces(),
                    callback
            );
        } else {
            FaceRegistrationManager.get().validateEmployeesForRebuild(context, employees, callback);
        }

        try {
            if (!latch.await(FACE_REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "Face registration timed out");
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "等待人脸注册超时",
                        "超时时间 " + FACE_REGISTRATION_TIMEOUT_SECONDS + " 秒"
                );
                return FaceRegistrationOutcome.timeout(holder.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "等待人脸注册被中断",
                    safeString(e.getMessage())
            );
            return FaceRegistrationOutcome.failure(holder.get());
        }

        int ok = 0;
        int fail = 0;
        for (FaceRegistrationManager.RegistrationResult result : holder.get()) {
            if (result != null && result.success) {
                ok++;
            } else if (result != null) {
                fail++;
            }
        }

        AppLogger.i(TAG, "Face registration: ok=" + ok + " fail=" + fail);
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "人脸注册结果",
                "成功 " + ok + "\n失败 " + fail
        );
        return FaceRegistrationOutcome.success(holder.get(), ok, fail);
    }

    private List<String> resolveTeamTimeRanges(DeviceDto.DeviceConfigData data, int teamBindingId) {
        if (data == null) {
            return java.util.Collections.emptyList();
        }
        for (DeviceDto.TeamOptionData team : data.teams) {
            if (team != null && team.id == teamBindingId) {
                return team.timeRanges;
            }
        }
        return java.util.Collections.emptyList();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isStaleEmployeeChange(Employee existing, long incomingUpdatedAt) {
        return existing != null
                && incomingUpdatedAt > 0
                && existing.updatedAt > 0
                && existing.updatedAt > incomingUpdatedAt;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String safeOpType(String opType) {
        return isBlank(opType) ? "sync" : opType.trim();
    }

    private String resolveEventGroup(String eventType) {
        if ("person_changed".equals(safeString(eventType))) {
            return InteractionLogger.GROUP_EMPLOYEE_SYNC;
        }
        if ("config_changed".equals(safeString(eventType))) {
            return InteractionLogger.GROUP_DEVICE_CONFIG;
        }
        return InteractionLogger.GROUP_GENERAL;
    }

    private List<EventResultDto.EmployeeResult> applyRegistrationResults(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            List<FaceRegistrationManager.RegistrationResult> registrationResults
    ) {
        if (employeeResults == null || employeeResults.isEmpty()) {
            return new ArrayList<>();
        }
        if (registrationResults != null) {
            for (FaceRegistrationManager.RegistrationResult registrationResult : registrationResults) {
                if (registrationResult == null || isBlank(registrationResult.empId)) {
                    continue;
                }
                EventResultDto.EmployeeResult employeeResult = employeeResults.get(registrationResult.empId);
                if (employeeResult == null || registrationResult.success) {
                    continue;
                }
                employeeResult.success = false;
                employeeResult.failMsg = safeString(registrationResult.failMsg);
            }
        }
        return new ArrayList<>(employeeResults.values());
    }

    private void markPendingRegistrationsFailed(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            LinkedHashMap<String, Employee> registrationTargets,
            String failMsg
    ) {
        if (employeeResults == null || employeeResults.isEmpty()
                || registrationTargets == null || registrationTargets.isEmpty()) {
            return;
        }
        for (String empId : registrationTargets.keySet()) {
            EventResultDto.EmployeeResult employeeResult = employeeResults.get(empId);
            if (employeeResult == null) {
                continue;
            }
            employeeResult.success = false;
            employeeResult.failMsg = safeString(failMsg);
        }
    }

    private void markMissingRegistrationResultsFailed(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            LinkedHashMap<String, Employee> registrationTargets,
            List<FaceRegistrationManager.RegistrationResult> registrationResults,
            String failMsg
    ) {
        if (employeeResults == null || employeeResults.isEmpty()
                || registrationTargets == null || registrationTargets.isEmpty()) {
            return;
        }
        Map<String, FaceRegistrationManager.RegistrationResult> reportedResults = new HashMap<>();
        if (registrationResults != null) {
            for (FaceRegistrationManager.RegistrationResult registrationResult : registrationResults) {
                if (registrationResult == null || isBlank(registrationResult.empId)) {
                    continue;
                }
                reportedResults.put(registrationResult.empId, registrationResult);
            }
        }
        for (String empId : registrationTargets.keySet()) {
            if (reportedResults.containsKey(empId)) {
                continue;
            }
            EventResultDto.EmployeeResult employeeResult = employeeResults.get(empId);
            if (employeeResult == null) {
                continue;
            }
            employeeResult.success = false;
            employeeResult.failMsg = safeString(failMsg);
        }
    }

    private List<EventResultDto.EmployeeResult> toEmployeeResultList(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults
    ) {
        return employeeResults == null ? new ArrayList<>() : new ArrayList<>(employeeResults.values());
    }

    private void publishPreparationOutcome(PunchApplication app, FaceRegistrationOutcome outcome) {
        if (app == null || outcome == null) {
            return;
        }
        if (!outcome.completed) {
            app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            return;
        }
        if (outcome.failed > 0 && outcome.succeeded > 0) {
            app.markPunchRecognitionReady(STATUS_MSG_PUNCH_PARTIAL_READY);
            app.reportStatusEvent(
                    "人脸入库部分失败：成功 " + outcome.succeeded + "，失败 " + outcome.failed + "，稍后自动重试",
                    PunchApplication.STATUS_LEVEL_ERROR
            );
            return;
        }
        if (outcome.failed > 0) {
            app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            app.reportStatusEvent(
                    "人脸入库失败：成功 " + outcome.succeeded + "，失败 " + outcome.failed,
                    PunchApplication.STATUS_LEVEL_ERROR
            );
            return;
        }
        app.markPunchRecognitionReady(STATUS_MSG_PUNCH_READY);
    }

    private static final class EmployeeSyncProcessingResult {
        final boolean overallSuccess;
        final boolean eventSuccess;
        final String failureMessage;
        final List<EventResultDto.EmployeeResult> employeeResults;

        private EmployeeSyncProcessingResult(boolean overallSuccess,
                                             boolean eventSuccess,
                                             String failureMessage,
                                             List<EventResultDto.EmployeeResult> employeeResults) {
            this.overallSuccess = overallSuccess;
            this.eventSuccess = eventSuccess;
            this.failureMessage = failureMessage;
            this.employeeResults = employeeResults == null ? new ArrayList<>() : employeeResults;
        }

        static EmployeeSyncProcessingResult success(boolean overallSuccess,
                                                    List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeSyncProcessingResult(overallSuccess, true, null, employeeResults);
        }

        static EmployeeSyncProcessingResult failure(String failureMessage,
                                                    List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeSyncProcessingResult(false, false, failureMessage, employeeResults);
        }
    }

    private static final class FaceRegistrationOutcome {
        final boolean completed;
        final List<FaceRegistrationManager.RegistrationResult> results;
        final int succeeded;
        final int failed;

        private FaceRegistrationOutcome(boolean completed,
                                        List<FaceRegistrationManager.RegistrationResult> results,
                                        int succeeded,
                                        int failed) {
            this.completed = completed;
            this.results = results == null ? new ArrayList<>() : results;
            this.succeeded = succeeded;
            this.failed = failed;
        }

        static FaceRegistrationOutcome success(List<FaceRegistrationManager.RegistrationResult> results,
                                               int succeeded,
                                               int failed) {
            return new FaceRegistrationOutcome(true, results, succeeded, failed);
        }

        static FaceRegistrationOutcome timeout(List<FaceRegistrationManager.RegistrationResult> results) {
            return new FaceRegistrationOutcome(false, results, 0, 0);
        }

        static FaceRegistrationOutcome failure(List<FaceRegistrationManager.RegistrationResult> results) {
            return new FaceRegistrationOutcome(false, results, 0, 0);
        }

        boolean isUsable() {
            return completed && failed == 0 || completed && succeeded > 0;
        }
    }

    private static final class EventProcessingOutcome {
        final boolean success;
        final String failureMessage;
        final List<EventResultDto.EmployeeResult> employeeResults;

        private EventProcessingOutcome(boolean success,
                                       String failureMessage,
                                       List<EventResultDto.EmployeeResult> employeeResults) {
            this.success = success;
            this.failureMessage = failureMessage;
            this.employeeResults = employeeResults == null ? new ArrayList<>() : employeeResults;
        }

        static EventProcessingOutcome success() {
            return new EventProcessingOutcome(true, null, new ArrayList<>());
        }

        static EventProcessingOutcome success(List<EventResultDto.EmployeeResult> employeeResults) {
            return new EventProcessingOutcome(true, null, employeeResults);
        }

        static EventProcessingOutcome failure(String failureMessage) {
            return new EventProcessingOutcome(false, failureMessage, new ArrayList<>());
        }

        static EventProcessingOutcome failure(String failureMessage,
                                              List<EventResultDto.EmployeeResult> employeeResults) {
            return new EventProcessingOutcome(false, failureMessage, employeeResults);
        }
    }
}
