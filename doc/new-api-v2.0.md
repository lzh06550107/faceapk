# new-api v2.0

更新时间：2026-07-18  
适用范围：`D:\code\faceapk` 当前 Android 客户端实现  
来源：基于现有 [new-api.md](D:\code\faceapk\doc\new-api.md) 与当前代码实现重新整理，文档内容以代码实际行为为准。

## 1. 总览

当前客户端实际使用的接口如下：

| 模块 | 方法 | 路径 | 是否鉴权 |
| --- | --- | --- | --- |
| 设备注册 | `POST` | `/v3/handheld/device/register` | 否 |
| 设备激活码获取 | `POST` | `/device/activate` | 否 |
| 登录 | `POST` | `/v3/handheld/auth/login` | 否 |
| Token 刷新 | `POST` | `/v3/handheld/auth/refresh` | 是 |
| 设备配置拉取 | `POST` | `/v3/handheld/device/get-config` | 是 |
| 设备心跳 | `POST` | `/v3/handheld/device/heartbeat` | 是 |
| 平台事件结果回传 | `POST` | `/v3/handheld/event/result` | 是 |
| 员工增量同步 | `POST` | `/v3/handheld/employee/sync` | 是 |
| 打卡上传 | `POST` | `/v3/handheld/clock/upload` | 是 |
| 打卡统计 | `POST` | `/v3/handheld/clock/statistics` | 是 |
| 健康检查 | `GET` | `/v3/handheld/health` | 否，客户端仅用于连通性探测 |

## 2. 基础约定

### 2.1 Base URL

默认业务地址：

```text
http://hzmq1.hainasmart.com.cn
```

当前客户端支持在高级配置中覆盖业务 `baseUrl`。除单独说明外，所有 `/v3/handheld/**` 接口都走该地址。

设备激活接口单独使用固定地址：

```text
http://park.hainasmart.com.cn:8898
```

即：

```text
http://park.hainasmart.com.cn:8898/device/activate
```

### 2.2 请求头

当前客户端的实际请求头约定：

```http
Content-Type: application/json
X-Device-Id: <device_id>
Authorization: Bearer <token>
```

说明：

- `X-Device-Id`：当前客户端对几乎所有请求都会带上该头，包括公开接口。
- `Authorization`：仅当本地已有 token 时自动添加；公开接口不依赖它。
- `Content-Type`：当前实现默认使用 JSON 请求体；未使用 multipart 上传。

### 2.3 通用响应包

当前客户端按以下统一结构解析：

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

判定成功的规则：

- `HTTP 2xx`
- 且业务 `code == 200` 或 `code == 0`

其余情况都视为失败。

### 2.4 device_id 约定

当前客户端的 `device_id` 生成逻辑：

1. 优先读取系统序列号。
2. 若系统序列号不可用，则退化为设备指纹字符串的 `SHA-256` 摘要前 `16` 位大写十六进制。
3. 最终值会在本地持久化，并作为请求头与请求体中的设备标识。

因此后端应将 `device_id` 视为设备稳定标识，而不是临时会话字段。

## 3. 设备注册

### 3.1 接口

```http
POST /v3/handheld/device/register
```

### 3.2 请求体

```json
{
  "company_id": 2,
  "device_name": "HUAWEI ABC-123",
  "device_id": "A1B2C3D4E5F60789",
  "ip": "192.168.1.123",
  "software_version": "1.0.0"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `company_id` | integer | 是 | 公司 ID，来自本地配置 |
| `device_name` | string | 是 | 当前客户端使用 `MANUFACTURER + MODEL` |
| `device_id` | string | 是 | 设备稳定标识 |
| `ip` | string | 是 | 当前 IPv4 地址，取不到时可能为空串 |
| `software_version` | string | 是 | App 版本号 |

### 3.3 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "device_id": "A1B2C3D4E5F60789"
  }
}
```

### 3.4 客户端使用说明

- 客户端只解析 `data.device_id`。
- 注册成功后仅标记“设备已注册”；当前代码不会采用服务端下发的新 `device_id` 覆盖本地设备标识。
- 因此后端返回的 `device_id` 应与请求中的设备标识一致，或至少语义兼容。

## 4. 设备激活码获取

### 4.1 接口

```http
POST /device/activate
```

Base URL 固定为：

```text
http://park.hainasmart.com.cn:8898
```

### 4.2 请求体

```json
{
  "device_id": "A1B2C3D4E5F60789"
}
```

### 4.3 响应体

当前客户端兼容以下几种返回格式：

#### 形式 A：标准对象

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "device_id": "A1B2C3D4E5F60789",
    "activation_code": "ACT-8X9K-2026-ABCD"
  }
}
```

#### 形式 B：兼容别名字段

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "deviceId": "A1B2C3D4E5F60789",
    "license_code": "ACT-8X9K-2026-ABCD"
  }
}
```

#### 形式 C：`data` 直接为字符串

```json
{
  "code": 200,
  "msg": "success",
  "data": "ACT-8X9K-2026-ABCD"
}
```

### 4.4 客户端解析兼容规则

当前客户端按如下优先级提取激活码：

- `data.activation_code`
- `data.activationCode`
- `data.code`
- `data.license_code`
- 若 `data` 本身是字符串，则直接视为激活码

建议后端统一使用：

- `data.device_id`
- `data.activation_code`

## 5. 登录

### 5.1 接口

```http
POST /v3/handheld/auth/login
```

### 5.2 请求体

```json
{
  "account": "admin",
  "password": "a123456!",
  "device_id": "A1B2C3D4E5F60789"
}
```

### 5.3 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "token_expire_at": 1784342400,
    "device": {
      "device_id": "A1B2C3D4E5F60789",
      "name": "手持机1",
      "line_code": "PKZ450",
      "line_name": "包装一线",
      "team": "A组"
    }
  }
}
```

### 5.4 客户端实际依赖字段

必需字段：

- `data.token`
- `data.token_expire_at`

可选字段：

- `data.device.device_id`
- `data.device.name`
- `data.device.line_code`
- `data.device.line_name`
- `data.device.team`

说明：

- 客户端当前不会依赖 `expires_in`；以 `token_expire_at` 为准。
- 本地默认登录账号是 `admin`，默认密码是 `a123456!`，但这只是 UI 默认值，不是接口协议要求。

## 6. Token 刷新

### 6.1 接口

```http
POST /v3/handheld/auth/refresh
```

### 6.2 鉴权

请求头需要：

```http
Authorization: Bearer <old_token>
```

### 6.3 请求体

```json
{
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

### 6.4 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...new",
    "token_expire_at": 1784947200
  }
}
```

### 6.5 客户端说明

- 当前客户端要求请求头和请求体都能提供旧 token。
- 客户端只解析新的 `token` 和 `token_expire_at`。

## 7. 设备配置拉取

### 7.1 接口

```http
POST /v3/handheld/device/get-config
```

### 7.2 请求体

当前客户端发送空对象：

```json
{}
```

### 7.3 响应体

推荐返回示例：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "device_id": "A1B2C3D4E5F60789",
    "account": "admin",
    "password": "a123456!",
    "line_binding_code": "PKZ450",
    "line_binding_name": "包装一线",
    "team_binding": 2,
    "team_binding_name": "A组",
    "check_count": 2,
    "need_update": false,
    "lines": [
      { "code": "PKZ450", "name": "包装一线" }
    ],
    "teams": [
      { "id": 2, "name": "A组", "time_ranges": ["08:00-12:00", "13:00-17:00"] }
    ],
    "update": {
      "need_update": false,
      "apk_url": "",
      "current_version": "1.0.0",
      "target_version": "1.0.0",
      "version_name": "1.0.0"
    },
    "baidu_params": {
      "match_threshold": 80,
      "face_threshold": 80,
      "liveness_check": true,
      "mask_detect": false,
      "timeout": 5,
      "recognize_distance": 2
    }
  }
}
```

### 7.4 客户端解析字段

| 字段 | 说明 |
| --- | --- |
| `device_id` | 设备 ID |
| `account` | 可回填到登录页 |
| `password` | 可回填到登录页 |
| `line_binding_code` | 当前绑定线体编码 |
| `line_binding_name` | 当前绑定线体名称 |
| `team_binding` | 当前绑定班组 ID |
| `team_binding_name` | 当前绑定班组名称 |
| `check_count` | 打卡次数 |
| `need_update` | 是否需要升级 |
| `lines[]` | 线体选项列表，字段为 `code`、`name` |
| `teams[]` | 班组选项列表，字段为 `id`、`name`、`time_ranges[]` |
| `update.need_update` | 升级标志 |
| `update.apk_url` | APK 下载地址 |
| `update.current_version` | 当前版本 |
| `update.target_version` | 目标版本 |
| `update.version_name` | 目标版本名 |
| `baidu_params.match_threshold` | 人脸匹配阈值，当前客户端按百分比整数读取并除以 `100` |
| `baidu_params.face_threshold` | 人脸检测阈值，当前客户端按百分比整数读取并除以 `100` |
| `baidu_params.liveness_check` | 是否开启活体 |
| `baidu_params.mask_detect` | 是否开启口罩检测 |
| `baidu_params.timeout` | 识别超时时间，单位秒 |
| `baidu_params.recognize_distance` | 识别距离档位：`<=1`=near，`2`=standard，`>=3`=far |

### 7.5 客户端行为说明

- 首次登录后会主动拉一次配置。
- 心跳收到 `config_changed` 事件后也会再次拉取配置。
- 当前实现里，事件驱动的配置同步会保留本地当前的线体/班组绑定，不会强制覆盖为服务端返回值；但会刷新 `lines`、`teams`、账号密码、更新信息和百度参数。

## 8. 设备心跳

### 8.1 接口

```http
POST /v3/handheld/device/heartbeat
```

### 8.2 请求体

```json
{
  "device_id": "A1B2C3D4E5F60789",
  "software_version": "1.0.0",
  "ip": "192.168.1.123"
}
```

### 8.3 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "server_time": 1784300000,
    "has_changes": true,
    "events": [
      {
        "event_cursor": "evt_20260718_0001",
        "event_type": "person_changed"
      },
      {
        "event_cursor": "evt_20260718_0002",
        "event_type": "config_changed"
      }
    ]
  }
}
```

### 8.4 客户端解析规则

- `server_time`：用于记录后端时间。
- `has_changes`：仅作为提示位。
- `events[]`：客户端真正依赖事件列表来决定是否执行同步。
- 事件游标兼容字段：`event_cursor` 或 `cursor`。

### 8.5 当前支持的事件类型

| `event_type` | 客户端行为 |
| --- | --- |
| `person_changed` | 调用员工增量同步，并重建本地人脸库 |
| `config_changed` | 调用设备配置拉取接口 |
| 其他 | 忽略，但仍会回传事件结果 |

## 9. 平台事件结果回传

### 9.1 接口

```http
POST /v3/handheld/event/result
```

### 9.2 请求体

#### 普通成功回传

```json
{
  "device_id": "A1B2C3D4E5F60789",
  "event_cursor": "evt_20260718_0002",
  "event_type": "config_changed",
  "success": true
}
```

#### 员工同步类回传

```json
{
  "device_id": "A1B2C3D4E5F60789",
  "event_cursor": "evt_20260718_0001",
  "event_type": "person_changed",
  "success": false,
  "failure_msg": "员工同步失败",
  "employees": [
    {
      "numbers": "E10001",
      "op_type": "update",
      "success": true,
      "fail_msg": ""
    },
    {
      "numbers": "E10002",
      "op_type": "update",
      "success": false,
      "fail_msg": "invalid face image"
    }
  ]
}
```

### 9.3 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `device_id` | 是 | 当前设备 ID |
| `event_cursor` | 是 | 心跳事件游标 |
| `event_type` | 是 | 事件类型 |
| `success` | 是 | 该事件整体是否成功 |
| `failure_msg` | 否 | 整体失败原因 |
| `employees` | 否 | 员工级处理结果，仅 `person_changed` 事件会带 |
| `employees[].numbers` | 是 | 员工编号 |
| `employees[].op_type` | 是 | 操作类型，如 `update`、`delete`、`sync` |
| `employees[].success` | 是 | 该员工处理是否成功 |
| `employees[].fail_msg` | 否 | 失败原因 |

## 10. 员工增量同步

### 10.1 接口

```http
POST /v3/handheld/employee/sync
```

### 10.2 请求体

```json
{
  "device_id": "A1B2C3D4E5F60789",
  "page": 1,
  "page_size": 500,
  "op_status": 1
}
```

### 10.3 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "has_more": true,
    "page": 1,
    "total_pages": 3,
    "server_time": 1784300000,
    "employees": [
      {
        "numbers": "E10001",
        "name": "张三",
        "face_image_url": "https://example.com/face/E10001.jpg",
        "op_type": "update",
        "op_time": 1784299000
      },
      {
        "numbers": "E10002",
        "op_type": "delete",
        "op_time": 1784299100
      }
    ]
  }
}
```

### 10.4 客户端实际依赖字段

| 字段 | 说明 |
| --- | --- |
| `has_more` | 是否还有下一页 |
| `page` | 当前页码 |
| `total_pages` | 总页数 |
| `server_time` | 服务端时间 |
| `employees[].numbers` | 员工编号，必填 |
| `employees[].name` | 员工姓名 |
| `employees[].face_image_url` | 人脸图片 URL |
| `employees[].op_type` | 操作类型，`delete` 会走删除逻辑 |
| `employees[].op_time` | 变更时间戳，用于判断是否为旧数据 |

### 10.5 客户端行为说明

- 当前客户端按页拉取，固定 `page_size=500`。
- 若 `op_type=delete`，客户端将本地员工标记删除。
- 若为新增/更新，客户端会更新本地员工信息并尝试做人脸注册。
- 人脸库重建与注册结果会影响该事件最终回传状态。

## 11. 打卡上传

### 11.1 接口

```http
POST /v3/handheld/clock/upload
```

### 11.2 请求体

当前客户端实际发送字段比旧文档更多，推荐以后端当前能力兼容如下结构：

```json
{
  "numbers": "E10001",
  "team_binding": 2,
  "line_binding_code": "PKZ450",
  "snap_time": 1784301200,
  "snap_image": "<base64>",
  "match_score": 0.93
}
```

### 11.3 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `numbers` | 是 | 员工编号 |
| `team_binding` | 是 | 班组绑定 ID |
| `line_binding_code` | 是 | 线体编码 |
| `snap_time` | 是 | 打卡时间，Unix 秒级时间戳 |
| `snap_image` | 否 | 当前客户端会附带打卡抓拍图的 Base64；无图时为空串 |
| `match_score` | 否 | 本次识别人脸匹配分数 |

### 11.4 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "record_id": 12345,
    "snap_time": 1784301200,
    "snap_time_str": "08:30:00",
    "dates": "2026-07-18",
    "attend_report_id": 67890,
    "attend_report_table": "hzq_attend_report_1_202607"
  }
}
```

### 11.5 客户端说明

- `team_binding <= 0` 时，客户端会直接判定为无效请求，不发接口。
- 客户端离线时会把打卡记录先存本地，恢复网络后再补传。
- 网络日志中会对 `snap_image` 做脱敏，只记录长度，不落完整 Base64。

## 12. 打卡统计

### 12.1 接口

```http
POST /v3/handheld/clock/statistics
```

### 12.2 请求体

```json
{
  "dates": "2026-07-18",
  "line_code": "PKZ450",
  "clock_index": 1,
  "clock_status": 1,
  "page": 1,
  "page_size": 20
}
```

### 12.3 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `dates` | 是 | 查询日期，格式 `YYYY-MM-DD` |
| `line_code` | 是 | 线体编码 |
| `clock_index` | 是 | 班次索引 |
| `clock_status` | 否 | 打卡状态过滤 |
| `page` | 是 | 页码 |
| `page_size` | 是 | 每页条数 |

### 12.4 响应体

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "date": "2026-07-18",
    "line_code": "PKZ450",
    "clock_index": 1,
    "clock_index_name": "第一次上班",
    "summary": {
      "total": 100,
      "clocked": 80,
      "unclocked": 18,
      "special": 2
    },
    "total": 100,
    "page": 1,
    "page_size": 20,
    "rows": [
      {
        "numbers": "E10001",
        "name": "张三",
        "line_id": 1,
        "line_name": "包装一线",
        "face_path": "https://example.com/face/E10001.jpg",
        "clock_time": "08:31:10",
        "clock_status": 1,
        "clock_status_text": "已打卡",
        "sign": "normal",
        "special_text": "",
        "late_minutes": 0,
        "early_minutes": 0
      }
    ]
  }
}
```

### 12.5 客户端解析字段

- `date`
- `line_code`
- `clock_index`
- `clock_index_name`
- `summary.total`
- `summary.clocked`
- `summary.unclocked`
- `summary.special`
- `total`
- `page`
- `page_size`
- `rows[].numbers`
- `rows[].name`
- `rows[].line_id`
- `rows[].line_name`
- `rows[].face_path`
- `rows[].clock_time`
- `rows[].clock_status`
- `rows[].clock_status_text`
- `rows[].sign`
- `rows[].special_text`
- `rows[].late_minutes`
- `rows[].early_minutes`

## 13. 健康检查

### 13.1 接口

```http
GET /v3/handheld/health
```

### 13.2 说明

- 当前客户端仅用它做后端连通性探测。
- 只要返回 `HTTP 2xx`，客户端就认为后端可达。
- 客户端不解析业务 `data`。

## 14. 与旧文档相比的关键修订

本版相对旧文档，按当前代码修正了以下差异：

1. 明确了 `X-Device-Id` 请求头是当前客户端固定发送的。
2. 明确了激活接口使用独立 Base URL：`http://park.hainasmart.com.cn:8898`。
3. 明确了登录与刷新接口以 `token_expire_at` 为实际有效期字段。
4. 明确了设备配置中的 `baidu_params` 具体字段及客户端映射方式。
5. 明确了心跳事件只实际处理 `person_changed` 和 `config_changed`。
6. 明确了事件结果回传接口 `/v3/handheld/event/result` 的请求体结构。
7. 明确了员工同步接口请求体固定包含 `page_size=500`、`op_status=1`。
8. 修正了打卡上传接口：当前客户端实际还会上传 `snap_image` 和 `match_score`。
9. 修正了打卡统计接口的实际请求/响应字段，以代码解析逻辑为准。
10. 明确了成功判定规则是业务 `code == 200` 或 `code == 0`。

## 15. 后端联调建议

1. 所有业务接口统一返回 `{ code, msg, data }`，避免客户端兼容分支继续扩大。
2. 设备激活接口统一返回 `data.activation_code`，不要混用多个别名字段。
3. 员工同步接口保证 `numbers`、`op_type`、`op_time` 语义稳定，否则会影响本地增量合并。
4. 打卡上传接口建议正式接纳 `snap_image` 与 `match_score` 字段，因为当前客户端已实际发送。
5. 若后端希望强制覆盖设备当前线体/班组绑定，需要与客户端同步调整 `config_changed` 的落地策略。