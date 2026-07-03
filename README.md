# 工厂手持机打卡 APK（纯 Java Android）

> 版本：v1.0 | 对应：手持机APK打卡设计_调动同步顺序修正版 + 百度离线人脸SDK 8.5

---

## 项目结构

```
app/src/main/
├── java/com/punch/app/
│   ├── PunchApplication.java        # Application：全局初始化、SDK init、启动 SyncService
│   ├── activity/
│   │   ├── SplashActivity.java      # 启动路由（判断 token 有效性）
│   │   ├── LoginActivity.java       # 登录页：账号登录 + 启动同步
│   │   └── MainActivity.java        # 主页：底部导航 + 网络监听 + 同步触发
│   ├── fragment/
│   │   ├── PunchFragment.java       # 打卡Tab：Camera1预览 + 帧识别 + 调动弹窗
│   │   ├── RecordsFragment.java     # 记录Tab：已签/未签列表 + 统计
│   │   └── ConfigFragment.java      # 配置Tab：阈值调整 + 同步 + 退出
│   ├── adapter/
│   │   ├── PunchRecordAdapter.java  # 打卡记录列表适配器
│   │   └── UnsignedEmployeeAdapter.java # 未签到员工列表适配器
│   ├── db/
│   │   └── DatabaseHelper.java     # SQLite：建表 + 全部 CRUD（employees/punch_records/
│   │                               #   transfer_records/sync_queue）
│   ├── face/
│   │   ├── FaceManager.java        # 百度SDK封装：init/register/recognize1N/rebuild
│   │   ├── FaceFileManager.java    # 人脸图片下载 + SHA256 校验
│   │   └── FaceRegistrationManager.java # 批量注册/刷新人脸
│   ├── model/
│   │   ├── Employee.java
│   │   ├── PunchRecord.java
│   │   ├── TransferRecord.java
│   │   └── SyncQueueItem.java
│   ├── network/
│   │   ├── ApiClient.java          # OkHttp 封装：get/post/put + 统一响应解析
│   │   └── ApiResponse.java
│   ├── service/
│   │   └── SyncService.java        # 后台同步：调动优先→打卡，5分钟定时
│   ├── utils/
│   │   ├── Constants.java          # 全局常量
│   │   ├── SessionManager.java     # SharedPrefs + EncryptedSharedPrefs（token）
│   │   ├── UlidGenerator.java      # 幂等 ID 生成
│   │   └── AppLogger.java
│   └── widget/
│       └── FaceFrameView.java      # 自定义View：四角识别框 + 扫描线动画
├── res/layout/                     # 所有布局 XML
├── res/drawable/                   # 按钮背景、图标、选择器
├── res/values/                     # strings / colors / styles
└── assets/
    ├── idl-license.face-android    # ⚠️ 百度授权文件（需自行放入）
    └── face-sdk-models/            # SDK 模型文件（已拷入）
app/libs/
└── facelibrary-release-8.5-*.aar  # 百度人脸 SDK（已拷入）
```

---

## 关键架构

### 1. 幂等打卡 ID
```
打卡：P{deviceId}_{ULID}
调动：T{deviceId}_{ULID}
```
`sync_queue` 对 `(action, record_id)` 加唯一约束，防止重复入队。

### 2. 调动优先同步顺序
```
SyncService.doSync()
  └─ syncTransfers()   → POST /employees/transfer
       └─ syncPunches()
            └─ is_transfer=1 的打卡：检查 transfer.is_synced==1 才上传
                                     否则跳过本轮，等下次
```

### 3. 离线 Token 策略
- 有效期 7 天；距过期 24h 内后台自动 refresh
- Token 有效 → 无需联网可打卡
- Token 过期 → 必须联网重新登录

### 4. 人脸库重建（App 重启后）
```
PunchApplication.initFaceSDK()
  └─ FaceManager.init()
       └─ FaceManager.rebuildFaceLibrary()   ← 从 DB 取 face_registered=1 的员工
            └─ extractFeature(localImagePath) + FaceSearch.pushPersonById()
```

### 5. Camera1 帧处理
```
Camera.setPreviewCallback(NV21 bytes)
  └─ 节流 600ms → 后台线程
       └─ FaceManager.recognizeFromNv21(nv21, w, h, angle)
            ├─ BDFaceImageInstance(nv21, h, w, NV21, angle, 0)
            ├─ FaceDetect.detect()
            ├─ FaceFeature.feature()
            └─ FaceSearch.search(threshold, topN=1, feature)
```

---

## SDK 集成说明

### 需要自行完成的步骤

1. **授权文件**：将百度控制台申请的 `idl-license.face-android` 放入
   `app/src/main/assets/`

2. **人脸 SDK 封装代码**：
   `FaceSDKManager`、`SdkInitListener` 已迁入
   `app/src/main/java/com/punch/app/face/`，不再依赖 Demo 的 `datalibrary` 目录。
3. **后端地址**：修改 `Constants.java` 中的 `BASE_URL`

---

## 构建与运行

```bash
# 确保已放入授权文件
cp idl-license.face-android app/src/main/assets/

# 构建 debug APK
./gradlew assembleDebug

# 构建 release APK
./gradlew assembleRelease

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 最低要求
- Android 7.0（API 24）
- 后置摄像头
- 2GB RAM（人脸模型加载约需 300MB）

---

---

## API 接口对应

| 功能 | 接口 |
|------|------|
| 登录 | `POST /auth/login` |
| 刷新 Token | `POST /auth/refresh` |
| 员工同步 | `GET /employees/sync` |
| 调动上报 | `POST /employees/transfer` |
| 单条打卡 | `POST /punch` |
| 批量打卡 | `POST /punch/batch` |
| 设备配置 | `GET/PUT /device/config` |
