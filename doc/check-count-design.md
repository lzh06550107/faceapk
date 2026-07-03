# 限制打卡人数设计说明

## 状态

- 当前仅沉淀设计，不实现代码
- 待需求进一步明确后再落地

## 背景

`同步设备配置` 接口新增了 `check_count` 字段。

根据 `doc/new-api.md` 的说明：

- `check_count` 表示限制打卡人数
- 相同人员重复打卡不重复计数

## 当前确认的业务规则

### 1. 限制维度

打卡人数限制按以下组合键生效：

- 日期
- 线体
- 班组
- 班次

可表示为：

`日期 + lineCode + teamBindingId + shiftName`

### 2. 日期口径

日期使用当前打卡记录最终落库使用的 `punchDate`。

这意味着：

- `specialTimeEnabled = false` 时，使用真实打卡时间计算出的日期
- `specialTimeEnabled = true` 时，使用伪造后的班次锚点时间计算出的日期

也就是“按伪造日期限制”。

### 3. 人数口径

人数按唯一员工数统计：

- 同一员工在相同 `日期 + 线体 + 班组 + 班次` 下重复打卡
- 不重复占用人数名额

建议统计口径：

- `DISTINCT emp_id`

### 4. 自由打卡

自由打卡也参与人数限制。

建议视为单独班次维度处理：

- `shiftName = 自由打卡`

即自由打卡在相同 `日期 + 线体 + 班组 + 自由打卡` 下单独限流。

### 5. 超限处理

当前讨论倾向于：

- 达到上限后，直接拦截
- 不保存本地打卡记录
- 不进入同步队列
- 不上报后端

但该点建议在正式实现前再次确认。

## 推荐实现方案

### 配置解析与存储

需要补充以下能力：

- `DeviceDto.DeviceConfigData.checkCount`
- `ApiService.parseDeviceConfig()` 解析 `check_count`
- `SessionManager.saveCheckCount(int)`
- `SessionManager.getCheckCount()`

### 本地统计查询

建议在 `DatabaseHelper` 中新增按限制维度统计唯一员工的方法。

建议方法：

```java
public List<String> getSignedEmpIds(String date, String lineCode, int teamBindingId, String shiftName)
```

建议 SQL 口径：

```sql
SELECT DISTINCT emp_id
FROM punch_records
WHERE punch_date = ?
  AND line_code = ?
  AND team_binding_id = ?
  AND shift_name = ?
```

## 对现有数据模型的影响

当前 `PunchRecord` / `punch_records` 表中只有：

- `punch_date`
- `line_code`
- `shift_name`

当前缺少：

- `team_binding_id`

因此如果未来要严格按 `日期 + 线体 + 班组 + 班次` 限制，建议补充：

- `PunchRecord.teamBindingId`
- `punch_records.team_binding_id`

并在打卡落库时保存当前班组 ID。

## 推荐拦截时机

建议在 `PunchFragment.doPunch(...)` 中、保存本地记录之前执行限制校验。

推荐顺序：

1. 计算本次记录的 `punchDate`
2. 读取当前：
   - `lineCode`
   - `teamBindingId`
   - `shiftName`
3. 查询当前组合键下已打卡的唯一员工集合
4. 判断当前员工是否已在集合中
5. 如果不在集合中，且人数已达到 `check_count`，则拦截

## 推荐判断逻辑

伪代码：

```java
int checkCount = SessionManager.get().getCheckCount();
if (checkCount > 0) {
    List<String> signedEmpIds = db.getSignedEmpIds(
            punchDate,
            lineCode,
            teamBindingId,
            shiftName
    );

    boolean alreadyCounted = signedEmpIds.contains(emp.id);
    if (!alreadyCounted && signedEmpIds.size() >= checkCount) {
        // 拦截并提示
        return;
    }
}
```

## 需要补充的提示文案

如果超限被拦截，建议给出明确提示，例如：

- `当前班次打卡人数已达上限`
- `当前班组当前班次打卡人数已达上限（N人）`

## 当前未决问题

以下问题当前未完全冻结，正式实现前建议再次确认：

### 1. 超限是否一定“不落本地”

当前倾向：

- 不保存
- 不上传

但如果后续有“超限但保留审计记录”的需求，方案需要调整。

### 2. 班次维度是否长期使用 `shiftName`

当前建议先按 `shiftName` 实现，因为现有本地记录已有该字段。

但从长期稳定性看，更理想的是：

- 增加并保存 `clockIndex`
- 未来按 `clockIndex` 作为班次维度

原因：

- `shiftName` 是显示字段
- 可能因文案、格式、国际化而变化
- `clockIndex` 更稳定

### 3. 历史数据迁移

如果新增 `team_binding_id` 字段，需要考虑：

- 老数据如何回填
- 老数据在离线记录页中的限制口径是否需要兼容

### 4. 在线/离线一致性

当前建议先限制“本地端可见的打卡记录”。

但如果未来后端也做相同限制，需要确认：

- 本地与后端是否完全同口径
- 本地离线期间与后端在线统计是否会短时不一致

## 建议的后续实现顺序

需求明确后，建议按下面顺序实现：

1. 配置字段解析与持久化
2. `PunchRecord` / 数据库增加 `team_binding_id`
3. `DatabaseHelper` 增加按 `日期 + 线体 + 班组 + 班次` 查询唯一员工的方法
4. `PunchFragment.doPunch(...)` 中增加拦截逻辑
5. 增加超限提示 UI
6. 补单元测试和回归测试

## 建议测试用例

### 1. 基础限制

- `check_count = 2`
- 同一 `日期 + 线体 + 班组 + 班次`
- 第 1、2 个不同员工允许打卡
- 第 3 个不同员工禁止打卡

### 2. 重复员工不重复计数

- `check_count = 2`
- 员工 A 已打卡
- 员工 A 再打卡仍允许
- 员工 B 允许
- 员工 C 禁止

### 3. 班组隔离

- 同一日期、同一线体、同一班次
- 班组 1 的人数达到上限
- 班组 2 仍可继续打卡

### 4. 班次隔离

- 同一日期、同一线体、同一班组
- 上班班次达到上限
- 下班班次不受影响

### 5. 自由打卡参与限制

- `自由打卡` 单独作为一类班次统计
- 自由打卡达到上限后，新的不同员工自由打卡应被拦截

### 6. 特殊时间模式

- `specialTimeEnabled = true`
- 使用伪造后的 `punchDate`
- 限制口径必须与最终记录面板展示日期一致

