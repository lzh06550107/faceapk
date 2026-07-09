# 限制打卡人数设计说明

## 状态
- 当前仅沉淀设计，不实现代码
- 需求口径已基本确认，可作为后续开发基线

## 背景

`同步设备配置` 接口新增了 `check_count` 字段。

业务含义：

- `check_count` 表示限制打卡人数
- 相同人员重复打卡不重复计数

## 已确认业务规则

### 1. 限制维度

打卡人数限制按以下组合键生效：

- 日期
- 线体
- 班组
- 班次

最终限制键：

`punchDate + lineCode + teamBindingId + clockIndex`

### 2. 日期口径

日期使用当前打卡记录最终落库使用的 `punchDate`。

这意味着：

- `specialTimeEnabled = false` 时，使用真实打卡时间计算出的日期
- `specialTimeEnabled = true` 时，使用伪造后的班次锚点时间计算出的日期

即：按最终落库日期限制。

### 3. 班次口径

班次维度使用 `clockIndex`，不再使用 `shiftName`。

规则：

- 自由打卡：`clockIndex = 0`
- 普通班次：使用实际班次索引

说明：

- `shiftName` 仅用于展示
- `clockIndex` 作为正式统计与限制维度，避免文案变化导致口径漂移

### 4. 人数口径

人数按唯一员工数统计。

建议统计口径：

- `DISTINCT emp_id`

规则：

- 同一员工在相同 `日期 + 线体 + 班组 + 班次` 下重复打卡
- 不重复占用人数名额

### 5. 自由打卡

自由打卡参与人数限制。

规则：

- 自由打卡单独作为一个班次维度
- 使用 `clockIndex = 0`
- 不与普通上班/下班班次共享人数名额

### 6. 超限处理

当人数达到上限后：

- 直接拦截
- 不保存本地打卡记录
- 不进入同步队列
- 不上报后端
- 被拦截的打卡完全无记录

### 7. `check_count` 特殊值

规则：

- `check_count <= 0` 视为“不限制”

### 8. 多设备并发与离线一致性

已接受以下事实：

- 多设备离线/并发场景下
- 本地限制与后端最终统计可能短时不一致

结论：

- 前端先按本地可见记录做限制
- 后端如有同口径限制，允许最终结果与前端短时不一致

### 9. 旧数据处理

规则：

- 旧记录全部清空
- 新记录必须带 `team_binding_id`

结论：

- 不做旧记录兼容统计
- 限制功能仅面向新版本新数据

### 10. 提示文案

超限时统一提示：

- `当前班次人数已达上限`

## 推荐实现方案

### 1. 配置解析与持久化

需要补充以下能力：

- `DeviceDto.DeviceConfigData.checkCount`
- `ApiService.parseDeviceConfig()` 解析 `check_count`
- `SessionManager.saveCheckCount(int)`
- `SessionManager.getCheckCount()`

### 2. 本地数据模型调整

需要补充以下字段：

- `PunchRecord.teamBindingId`
- `PunchRecord.clockIndex`

数据库表 `punch_records` 需要补充：

- `team_binding_id`
- `clock_index`

说明：

- `team_binding_id` 用于班组隔离
- `clock_index` 用于正式班次限制维度

### 3. 本地统计查询

建议在 `DatabaseHelper` 中新增按限制维度统计唯一员工的方法。

建议方法：

```java
public List<String> getSignedEmpIds(
        String punchDate,
        String lineCode,
        int teamBindingId,
        int clockIndex
)
```

建议 SQL：

```sql
SELECT DISTINCT emp_id
FROM punch_records
WHERE punch_date = ?
  AND line_code = ?
  AND team_binding_id = ?
  AND clock_index = ?
```

### 4. 建议索引

为避免离线记录增多后查询变慢，建议增加索引：

```sql
CREATE INDEX idx_punch_limit
ON punch_records(punch_date, line_code, team_binding_id, clock_index, emp_id)
```

## 推荐拦截时机

建议在 `PunchFragment.doPunch(...)` 中、保存本地记录之前执行限制校验。

推荐顺序：

1. 计算本次记录的 `punchDate`
2. 读取当前：
   - `lineCode`
   - `teamBindingId`
   - `clockIndex`
3. 读取 `checkCount`
4. 若 `checkCount <= 0`，直接放行
5. 查询当前组合键下已打卡的唯一员工集合
6. 判断当前员工是否已在集合中
7. 如果当前员工不在集合中，且人数已达到 `checkCount`，则拦截

## 推荐判断逻辑

伪代码：

```java
int checkCount = SessionManager.get().getCheckCount();
if (checkCount <= 0) {
    return;
}

List<String> signedEmpIds = db.getSignedEmpIds(
        punchDate,
        lineCode,
        teamBindingId,
        clockIndex
);

boolean alreadyCounted = signedEmpIds.contains(emp.id);
if (!alreadyCounted && signedEmpIds.size() >= checkCount) {
    showLimitMessage("当前班次人数已达上限");
    return;
}
```

## 对现有代码的影响

### 1. `PunchFragment`

需要补充：

- 当前打卡对应的 `clockIndex`
- 当前 `teamBindingId`
- 保存前人数限制校验

### 2. `PunchRecord`

需要新增：

- `teamBindingId`
- `clockIndex`

### 3. `DatabaseHelper`

需要补充：

- 数据库升级
- 新增字段
- 新增唯一员工统计查询方法
- 可选：新增索引

### 4. `ApiService / DeviceConfig`

需要补充：

- `check_count` 配置解析
- 本地持久化

## 推荐实现顺序

1. 配置字段解析与持久化
2. `PunchRecord` / 数据库增加 `team_binding_id`、`clock_index`
3. 清理旧记录数据
4. `DatabaseHelper` 增加按 `日期 + 线体 + 班组 + 班次` 查询唯一员工的方法
5. `PunchFragment.doPunch(...)` 中增加拦截逻辑
6. 增加超限提示 UI
7. 补单元测试和回归测试

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
- 班组 1 人数达到上限
- 班组 2 仍可继续打卡

### 4. 班次隔离

- 同一日期、同一线体、同一班组
- 上班班次达到上限
- 下班班次不受影响

### 5. 自由打卡参与限制

- 自由打卡使用 `clockIndex = 0`
- 自由打卡达到上限后
- 新的不同员工自由打卡应被拦截

### 6. 特殊时间模式

- `specialTimeEnabled = true`
- 使用伪造后的 `punchDate`
- 限制口径必须与最终记录面板展示日期一致

### 7. 无限制场景

- `check_count = 0`
- `check_count = -1`
- 任意人数均允许打卡

### 8. 超限无痕

- 触发超限后
- 本地记录表无新增
- 同步队列表无新增
- 无后端上报
