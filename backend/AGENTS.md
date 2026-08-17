# backend/AGENTS.md

本文件记录后端当前架构、领域边界与后续修改必须保持的约束。根目录协作规则、编码要求与 GitHub Flow 请看 [`../AGENTS.md`](../AGENTS.md)；完整端到端功能逻辑请看 [`../README.md`](../README.md)。

## 1. 技术基线

- Java 21。
- Spring Boot 3.5.14。
- Spring MVC + Jakarta Validation。
- Spring Data JPA + Hibernate Community Dialect。
- SQLite JDBC 3.49.1.0。
- Apache POI 5.3.0，用于 XLSX。
- Thymeleaf + OpenHTMLtoPDF，用于 A4 横向 PDF。
- Spring Mail / JavaMailSender，用于 SMTP。
- Maven 在 `prepare-package` 阶段使用 Node 22.12.0 执行前端 `npm ci` 与 `npm run build`。

当前不是「一般关系数据库可替换」架构，也不是单表 MVP。实现依赖 SQLite 的运行、锁与持久化特性，实际有八张业务/设置表。

## 2. 启动与运行架构

`QnCalendarApplication` 启动前先调用 `ApplicationDataDirectory.prepareDefaultDirectory()`：

```text
JVM system property qn.calendar.data-dir
  → 环境变量 QN_CALENDAR_DATA_DIR
  → ${user.home}/.qn-calendar
```

解析后建立目录，并设置 `qn.calendar.data-dir`；SQLite 文件是：

```text
${qn.calendar.data-dir}/qn-calendar.db
```

注意：

- 这个解析发生在 Spring 读取 `.env` 前；把 `QN_CALENDAR_DATA_DIR` 只写进 `.env` 不会生效。
- Docker Compose 固定设置 `/data`，使用 `qn-calendar-data` named volume。
- `application.yml` 的 multipart 单档与请求上限均为 20MB。
- 正式环境使用 `spring.jpa.hibernate.ddl-auto=update`。
- 项目没有 Flyway、Liquibase、SQL migration 或固定 schema 文件；数据库升级完全依赖 Hibernate update。

Maven package 流程：

```text
下载 Node 22.12
  → frontend/npm ci
  → frontend/npm run build
  → 输出到 backend/target/classes/static
  → Spring Boot executable jar
```

Spring Boot 同时提供 `/api/**` 与 Vue production build。

## 3. 包结构与模块职责

| Package | 职责 |
|---|---|
| `com.qn.calendar` | 应用入口 |
| `common` | `ApiError` 与集中例外转换 |
| `config` | application `Clock`、数据目录 |
| `workorder.controller` | 工单、片段、统计、Email REST 入口 |
| `workorder.dto` | HTTP request/response 与统计 projection |
| `workorder.entity` | 工单、排程片段、暂停记录 |
| `workorder.repository` | JPA 查询、重叠检测、自动顺延候选 |
| `workorder.service` | 导入、查询、排程、片段、计时、PDF/Email |
| `workorder.util` | 15 分钟边界与工时计算 |
| `settings.controller` | 基础设置、SMTP、收件者 REST 入口 |
| `settings.dto/model/entity/repository/service` | 设置与收件者领域 |
| `web` | 静态资源 cache 与 SPA fallback |
| `desktop` | 桌面浏览器、单一实例、系统托盘、本地 URL |

服务边界：

- `WorkOrderImportService`：XLSX 表头/资料行解析、逐行错误、去重、upsert，以及手动建立待排工单与预估工时。
- `WorkOrderService`：待排查询、日历查询、完工统计、待排工时、整单删除/清空、保留的整单完成/reopen。
- `WorkOrderScheduleService`：仅把建立排程委派给 `WorkOrderSegmentService`，不拥有验证规则。
- `WorkOrderSegmentService`：片段建立/移动/删除/拆分/融合、重叠验证、暂停/继续/完成、自动顺延。
- `WorkOrderEmailService`：读取报表资料、建立 view model、Thymeleaf、PDF、SMTP。
- `AppSettingsService`：singleton 基础设置与 SMTP。
- `ImportFieldSettingsService`：固定字段别名、自定义别名与加急文字规则的读取、验证和导入快照。
- `EmailRecipientService`：常用收件者 CRUD 与寄送成功后的使用纪录。

不要把片段规则移回 controller，也不要把所有 work-order 逻辑合并成单一 service。

## 4. 数据模型

### 4.1 `work_order`

| 字段 | 语义 |
|---|---|
| `id` | 主键 |
| `order_no` | 唯一订单编号，最长 80 |
| `source` | `QIANNIU` / `XIAOHONGSHU` / `CUSTOM`；旧资料 null 由 getter 视为 `QIANNIU` |
| `source_name` | 自定义来源名称；内建来源由 enum 提供固定中文名称 |
| `buyer_nickname` | 买家昵称；当前导入不会写入，API 也没有更新入口 |
| `remark` | 合并后的买家留言/商家备注，最长 1000 |
| `price` | 订单价格，`decimal(14,2)` |
| `estimated_minutes` | 由导入价格计算的预估分钟 |
| `actual_minutes` | 待排人工值，或已排所有片段分钟总和 |
| `urgent` | 加急 |
| `latest_ship_time` | 最晚发货 |
| `order_time` | 订单月份与完工统计筛选依据 |
| `status` | `PENDING` / `SCHEDULED` / `DONE` |
| `scheduled_start` | 所有片段最早开始摘要 |
| `scheduled_end` | 所有片段最晚结束摘要 |
| `completed_at` | 完成时间 |
| `created_at` / `updated_at` | 持久化时间戳 |

`actual_minutes` 是片段长度总和，不一定等于 `scheduled_end - scheduled_start`，因为同工单可有空档。

### 4.2 `work_order_segment`

- 多对一属于 `work_order`。
- 保存 `scheduled_start`、`scheduled_end` 与建立/更新时间。
- 工单对片段为 cascade all + orphan removal。

### 4.3 `work_order_segment_pause`

- 多对一属于片段。
- `paused_at` 必填，`resumed_at` 可空；空值代表正在暂停。
- 有片段索引，以及 `work_order_segment_id,resumed_at` 开放暂停查询索引。
- 删除/融合片段前必须显式处理 pause，不能假定数据库级 cascade。

### 4.4 `app_setting`

- 固定 singleton ID `1`。
- 保存预估工时基础金额、周表默认开始时间。
- `app_setting_order_source_option` 按顺序保存手动新增工单可选的来源名称。
- 保存寄件 Email、SMTP host/port/security/auth code。
- SMTP auth code 当前以明文保存在 SQLite；API 不回传它。

### 4.5 `email_recipient`

- Email 唯一，最长 320。
- 姓名可空；手动 CRUD 时 service 要求姓名，寄信成功自动建立时可空。
- 保存 `usage_count`、`last_used_at` 与建立/更新时间。
- Service 会 trim、lowercase，并以忽略大小写方式防重。

### 4.6 `import_field_alias`

- 只保存使用者自定义别名；系统别名固定在 `ImportFieldKey`，不重复写入数据库。
- 保存 `field_key`、原始 `alias` 与 `normalized_alias`。
- `normalized_alias` 全局唯一，避免一个表头同时属于两个 canonical 字段。

### 4.7 `import_urgent_match_rule`

- 只保存使用者自定义加急文字；系统规则固定在 service。
- 保存原始 `text`、全局唯一的 `normalized_text` 与 `EXACT` / `CONTAINS`。
- `红旗` 不是系统规则；使用者可从字段设置自行加入。

## 5. 状态与摘要

```text
新导入 → PENDING
建立第一个片段 → SCHEDULED
删除最后片段 / 整单 unschedule → PENDING
片段完成或整单 done → DONE
```

完成在现行 UI 中不可复原。`PATCH /{id}/reopen` 虽存在，但当前实现会拒绝 `DONE`；不要把它记录或实现成可见「取消完成」功能，除非需求明确修改领域规则与测试。

每次 normalize 后：

- `scheduled_start` = 第一片段开始。
- `scheduled_end` = 最后一片段结束。
- `actual_minutes` = 所有片段各自长度总和。
- 非 DONE 工单变为 SCHEDULED，并清空 `completed_at`。
- DONE 工单更新摘要时保持 DONE。

## 6. REST API

### 6.1 工单与片段

| Method | Path | 行为 |
|---|---|---|
| `POST` | `/api/work-orders/import` | multipart XLSX 导入 |
| `POST` | `/api/work-orders` | 手动新增 PENDING，成功 201；重复订单编号返回 409 |
| `GET` | `/api/work-orders/pending` | 待排清单 |
| `GET` | `/api/work-orders/calendar?dateFrom&dateTo` | 区间内 SCHEDULED/DONE 片段 |
| `GET` | `/api/work-orders/statistics/completed` | 全部完工统计 |
| `PATCH` | `/api/work-orders/{id}/schedule` | 新增排程片段 |
| `PATCH` | `/api/work-orders/segments/{segmentId}` | 移动/resize 片段 |
| `DELETE` | `/api/work-orders/segments/{segmentId}` | 依片段原日期移出日历 |
| `POST` | `/api/work-orders/segments/{segmentId}/split` | 拆分片段 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/done` | 以当前时间完成整张工单 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/pause` | 暂停 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/resume` | 继续 |
| `PATCH` | `/api/work-orders/{id}/duration` | 调整 PENDING 工时 |
| `PATCH` | `/api/work-orders/{id}/unschedule` | 清除整单片段与暂停 |
| `DELETE` | `/api/work-orders/{id}` | 删除 PENDING，成功 204 |
| `PATCH` | `/api/work-orders/{id}/done` | 保留的直接整单完成端点 |
| `PATCH` | `/api/work-orders/{id}/reopen` | 保留端点；当前拒绝 DONE |
| `POST` | `/api/work-orders/schedule-email` | 发送 PDF Email，成功 204 |

现行前端 UI 不使用 `/{id}/unschedule`、整单 `/{id}/done`、`/{id}/reopen`。

片段 create/update/split/delete 没有统一的 PENDING/SCHEDULED/DONE 状态 gate；例如 API 可对 DONE 工单建立或拆分片段，normalize 后仍保持 DONE。不要把前端隐藏按钮误写成后端领域限制。

### 6.2 设置与收件者

| Method | Path | 行为 |
|---|---|---|
| `GET` | `/api/settings` | 读取或建立默认 singleton |
| `PUT` | `/api/settings` | 保存基础金额与周表开始时间 |
| `PUT` | `/api/settings/email-sender` | 保存 SMTP |
| `GET` | `/api/settings/import-fields` | 读取系统/自定义字段别名与加急文字规则 |
| `PUT` | `/api/settings/import-fields` | 以完整快照替换自定义字段别名与加急文字规则 |
| `GET` | `/api/email-recipients` | 常用收件者 |
| `POST` | `/api/email-recipients` | 手动新增，成功 201 |
| `PUT` | `/api/email-recipients/{id}` | 手动编辑 |
| `DELETE` | `/api/email-recipients/{id}` | 删除，成功 204 |

## 7. XLSX 导入

### 7.1 文件与表头

- 只读取第一个 sheet。
- 第一资料行必须是表头；空白资料行略过。
- POI 会计算公式值。
- 表头 canonicalize：trim、转小写、移除空白、`_`、`-`。
- 即使备注能解析期限，也必须存在订单编号、价格、最晚发货三个 canonical 字段。

表头别名：

| Canonical | 支持名称 |
|---|---|
| `orderNo` | 订单编号（繁/简）、`订单号` |
| `price` | 订单价格、买家实付金额、价格、金额（繁/简）、`用户应付金额(元)` |
| `urgent` | 加急、急件、备注标签（繁/简）、`包裹备注标记` |
| `buyerMessage` | 买家留言（繁/简）、`用户备注` |
| `merchantRemark` | 商家备注（繁/简）、`包裹备注信息` |
| `paidAt` | 付款/支付/订单/下单时间与日期的繁简别名 |
| `latestShipTime` | 应发货时间、最晚发货日期/时间（繁/简）、`承诺发货时间` |

系统别名由 `ImportFieldKey` 提供，自定义别名由 `ImportFieldSettingsService#getImportSnapshot()` 合并。新增系统别名前先补测试，不能破坏现有繁体兼容。同字段同时命中一个系统别名和一个自定义别名时，自定义别名优先；同时命中两个系统别名或两个自定义别名时必须拒绝整份文件。

小红书：

- 有 `小红书编码` 表头，或使用 `订单号` 且订单号符合 `^P\d+$` 时，来源为 `XIAOHONGSHU`；其余来源为 `QIANNIU`。
- 小红书只处理 `订单状态 = 待配货`；其他状态在解析金额/期限前略过并累计 `skippedCount`。
- 有 `小红书编码` 的工作簿缺少 `订单状态` 时整份拒绝；小红书资料行的状态空白时记录逐行错误。
- 已存在订单的来源与新导入来源不一致时，整次导入回滚。

### 7.2 资料行解析

- 订单编号不可空。
- 价格去除逗号、`NT$`、`¥`、`￥`、`$`；允许 0，不允许负数。
- 加急系统规则接受 `true/yes/y/1/是` 完全匹配，及包含「加急」「急件」；导入时另合并字段设置中的自定义完全/包含规则。
- 备注按顺序组合：
  - `买家留言：...`
  - `商家备注：...`
  - 都空时为 `无任何备注`
- 订单时间可空；有值但格式错误时整列失败。

预估工时：

```text
estimated_minutes = ceil(price / estimatedHourlyBaseAmount) × 60
```

这是按整小时向上取整，不是对分钟做比例换算。

### 7.3 最晚发货

优先级：

1. 商家备注中的发货/收到日期。
2. 买家留言中的发货/收到日期。
3. 最晚发货/应发货字段。

备注解析：

- `M/D`、`M.D`、`M月D日` 等月日使用 application Clock 当前年。
- 只有「D号」时，使用订单时间的月份；没有订单月份则继续 fallback。
- 无效月日不抛出，继续下一来源。

fallback 字段支持：

- ISO `LocalDateTime`。
- `yyyy-MM-dd HH:mm[:ss]`、非补零形式与 `/` 分隔。
- `yyyy-MM-dd`、`yyyy/M/d`。
- 文本中一个或多个 `yyyy-MM-dd HH:mm[:ss]前`，取最早值。
- Excel date cell。
- 纯日期或 midnight Excel date 转为 `23:59:59`。

### 7.4 去重与更新

- `order_no` 有数据库唯一约束。
- 每个资料行先完整解析；失败资料行加入 `errors`，不留下部分更新。
- 同档重复订单以最后一笔有效资料行为准。
- 后续错误重复资料行不会覆盖前一笔有效资料。
- `createdCount`、`updatedCount` 以唯一有效订单编号计算。
- 整次导入在一个交易内。

更新既有工单时：

- 更新 source、remark、price、estimated、urgent、deadline、orderTime。
- 保留 ID、orderNo、状态、片段、暂停、完成时间、排程摘要。
- SCHEDULED/DONE 保留 `actualMinutes`。
- PENDING 只有在 `actualMinutes == 旧 estimatedMinutes` 时跟随新 estimate；已人工调整则保留。
- 不重新验证既有片段是否仍符合新 deadline；将已排或 DONE 工单的期限改早可直接形成 `overdue`。

## 8. 待排与查询

待排固定排序：

```text
latest_ship_time ASC, urgent DESC, created_at ASC
```

只有 PENDING：

- 可以 `PATCH /duration`。
- 可以 `DELETE /{id}`。

`actualMinutes` 必须至少 15、为正数且是 15 分钟倍数。不要加入 `sort_order` 或使用者自定义排序。

日历查询：

- `dateFrom`、`dateTo` 都是 inclusive 业务日期。
- `dateTo < dateFrom` 为 400。
- 只查 SCHEDULED、DONE。
- 每个片段 response 包含订单 `source`、整单 `totalMinutes`、整单 `pausedMinutes`、当前 `paused`、`overdue`、`scheduleStartLocked`、`latestPausedAt`。
- `overdue = segment.scheduledEnd > workOrder.latestShipTime`。

## 9. 手动排程与重叠

真实排程粒度是 **15 分钟**。

一般 create/update 必须：

1. `scheduledEnd > scheduledStart`。
2. 开始、结束的秒与纳秒为 0，分钟落在 15 分钟边界。
3. duration 为 15 分钟倍数。
4. `scheduledEnd <= latestShipTime`。
5. 不与其他工单的 SCHEDULED/DONE 片段重叠。

重叠采用半开区间：

```text
existing.start < requested.end
AND existing.end > requested.start
```

所以不同工单首尾相接允许。

同工单 normalize：

- create/update 后，相邻或重叠片段融合。
- 保留较早片段，结束取较晚值。
- 被删除片段的 pause 迁移到保留片段。
- 融合后同步工单摘要。

显式 `split` 是刻意例外：

- `splitAt` 必须是片段内部的 15 分钟边界。
- 正在暂停的片段不可拆。
- 今日有暂停历史时，拆分点不可早于最后暂停时间向上取整后的边界。
- split 不执行 normalize，因此会保留两个相邻片段。
- 后续 create/update normalize 可能再次把它们融合。

没有自动初始排程算法；但计时超过排定结束时会执行后续冲突链自动顺延，这不是同一概念。

## 10. 今日暂停历史的移动与 resize

`scheduleStartLocked` 条件：

- 片段存在任一最新暂停记录，不要求仍开放。
- 工单未 DONE。
- 片段开始日期等于 application Clock 的今天。

规则：

- 等长移动允许开始和结束一起平移。
- resize 必须保持开始不变，只能把结束延后；不可缩短。
- 移动或 resize 仍需通过一般 15 分钟、期限与不同工单重叠验证。
- 操作后清理不在新区间内的 pause：
  - `pausedAt` 必须在片段闭区间内。
  - 已关闭 pause 的 `resumedAt` 也必须在闭区间内。
  - 开放 pause 只要 `pausedAt` 仍在区间即可保留。
- 若操作后片段融合，有效 pause 必须迁移到存活片段。

## 11. 暂停、继续、完成与自动顺延

### 11.1 暂停

- 使用 application Clock 当前时间，去掉纳秒但保留秒。
- 只允许未 DONE、片段开始日期等于当前业务日期、现在不早于开始。
- 不要求现在早于原结束；超过结束时先延长。
- 同一片段不可同时存在两个开放 pause。
- 支持多次暂停/继续周期。

### 11.2 继续

- 必须存在开放 pause。
- DONE 不可继续。
- `resumedAt` 不可早于 `pausedAt`。
- 可在跨日后继续。
- 暂停分钟使用 `Duration.toMinutes()`，每个区间不足一分钟的秒数会截断。

暂停或继续晚于原结束时：

- 当前片段结束向上取到下一个 15 分钟边界。
- 查询 `scheduledEnd > 原结束` 的其他 SCHEDULED/DONE 候选，并按开始时间排序。
- 对与新 cursor 相交的候选依序平移；遇到第一张 `start >= cursor` 的片段就停止。平移保持原 duration，再把新结束向上取边界。
- 同步所有受影响工单摘要。
- 这条自动顺延路径不检查各工单最晚发货，因此允许产生 `overdue`。
- 自动顺延只修改候选片段时间，不同步移动候选片段已有的 pause 时间戳。

### 11.3 片段完成

- 如果仍在暂停，先以完成时间关闭开放 pause；完成早于暂停会失败。
- 若完成时间与片段开始同日、晚于开始且不等于原结束，片段结束直接改成完成时间：
  - 可能缩短，也可能延长。
  - 保留秒数，不向上取 15 分钟。
  - 只有延长时才顺延后续冲突链。
- 不同日完成，或完成时间不晚于开始时，不改片段结束。
- 自动完成/顺延不重新验证最晚发货。
- 最后把整张工单设为 DONE，`completedAt` 为完成时间。
- DONE 仍保留在日历并参与不同工单重叠检测。

### 11.4 保留的整单端点

`PATCH /{id}/done` 只做：

```text
status = DONE
completedAt = application clock now
```

它不要求已排程、不改片段、不关闭 pause。现行 UI 不使用。

`PATCH /{id}/reopen` 当前拒绝 DONE；对非 DONE 会调用 entity `reopen()` 设为 SCHEDULED。不要在未明确修正规格、测试与 UI 前扩展使用。

## 12. 移出日历与删除

`DELETE /segments/{segmentId}` 必须由后端根据被删片段原 `scheduledStart` 和 application Clock 今天判断：

- 今日片段：
  - 删除整单所有 pause。
  - 删除整单所有片段。
  - 清空排程和完成时间。
  - `status = PENDING`。
  - `actualMinutes = estimatedMinutes`。
- 非今日片段：
  - 删除该片段 pause 与片段。
  - 有其他片段时保持已排。
  - 最后一段删除后回 PENDING，并恢复 estimated 工时。

`PATCH /{id}/unschedule` 不看片段日期，直接清除整单全部 pause/片段并回 PENDING。PENDING 调用会返回 409。

待排 `DELETE /{id}` 只允许 PENDING；SCHEDULED/DONE 返回 409。

## 13. 完工统计

只统计 DONE：

```text
scheduledTotalMinutes = 所有片段分钟总和
pausedMinutes = 所有暂停区间分钟总和
actualTotalMinutes = max(0, scheduledTotalMinutes - pausedMinutes)
deltaMinutes = actualTotalMinutes - estimatedMinutes
hourlyRate = price × 60 / actualTotalMinutes
```

- 时薪四舍五入到两位；actual 为 0 时回 `null`。
- 开放 pause 以 `completedAt` 作为 fallback；没有完成时间时才用 application Clock now。
- 排序为：非空 orderTime 优先、orderTime DESC、deadline ASC、createdAt ASC。
- 月份筛选依据 `orderTime`，不是 `completedAt`。
- 全部统计包含 orderTime null；按月份查询不会包含 null。

## 14. 全局设置与 SMTP

默认 singleton：

```text
estimatedHourlyBaseAmount = 100
weekViewDefaultStartTime = 06:00
orderSourceOptions = [千牛, 小红书]
```

- 基础金额必须大于 0、整数部分最多 12 位且小数最多两位。
- 周表开始时间必须为 `HH:mm` 的 30 分钟边界。
- 订单来源选项为 1–20 个、每项最长 80 字，trim 后忽略大小写不可重复；既有设置缺少选项时补入千牛与小红书。
- 初次读取若 singleton 不存在会写入默认值。
- 旧资料的周表开始时间为空时会补 `06:00`。

SMTP：

- sender Email、host、port、security、auth code 全部齐全才算 configured。
- sender Email 最多 320 字、host 最多 255 字、auth code 最多 1024 字。
- security 为 `NONE`、`SSL`、`STARTTLS`。
- 后端 port 接受 1–65535；前端目前只提供 465/587。
- 初次设置必须有 auth code。
- 后续 `null`/空 auth code 表示保留旧值，当前 API 不能清除 auth code。
- response 回完整 sender Email 供编辑，也回 masked Email；绝不回 auth code。
- SMTP client 每次寄送动态建立。
- 当前没有配置 connect/read/write timeout；不要在不了解重复寄送风险时增加自动重试。

## 15. Email 收件者

手动 CRUD：

- 姓名必填、trim、最多 120。
- Email 必填、合法、trim、lowercase、最多 320。
- 忽略大小写防重复。

排序：

```text
lastUsedAt DESC → usageCount DESC → name ASC → email ASC
```

寄送成功后：

- normalize、去重收件 Email。
- 找不到则建立 `name = null` 的收件者。
- `usageCount + 1`，更新 `lastUsedAt`。
- 使用 `REQUIRES_NEW` 独立写交易。

SMTP 失败时不可记录使用次数。

## 16. PDF 与 Email

`ScheduleEmailViewType`：

- `WEEK`
- `MONTH`
- `COMPLETED_STATS`

验证：

- 收件者不可空，DTO 会验证每个 Email。
- subject 不可空。
- WEEK/MONTH 必须有有效起讫日期。
- MONTH 强制使用 `dateFrom` 所在月首/月末。
- COMPLETED_STATS 可同时传 `dateFrom = null`、`dateTo = null` 代表全部；有月份时同样强制整月。

报表：

- 跨日片段裁切为每天一列/row。
- 周表可超过 7 天，每 7 天一节并分页。
- 周表时间轴按每节最早开始向下取整小时、最晚结束向上取整小时；无资料默认 09:00–18:00。
- 月表从周日开始；月底前已无后续工单时可提前截断并显示提示。
- 完工统计字段与前端表格一致。
- Thymeleaf HTML 只作为 OpenHTMLtoPDF 输入，不作为邮件正文。
- PDF 固定 A4 landscape 297×210mm。
- 邮件正文为空字串，只附 PDF。
- 附件名由 view type 和日期产生，不使用 request subject：
  - `周表 - yyyy-MM-dd - yyyy-MM-dd.pdf`
  - `月表 - yyyy-MM.pdf`
  - `完工统计表 - 全部|yyyy-MM.pdf`
- 中文文件名同时写 RFC 5987 `filename*` 与 MIME encoded-word `filename`。
- PDF 字体从 macOS、Windows、Linux 候选路径选择第一个存在的中文字体；Docker 安装文泉驿。

发送顺序：

```text
验证 request
  → 短查询取得资料
  → 交易外建立 HTML/PDF
  → 动态 SMTP 发送
  → 成功后 REQUIRES_NEW 记录收件者
```

`WorkOrderEmailService` 没有外层长交易，避免在 PDF/SMTP 等待期间持有 SQLite 交易。`recordUsed` 仍可能独立失败，不要承诺「SMTP 成功后绝不可能回错误」。

## 17. 时区与时间来源

application Clock：

- 默认 `Asia/Shanghai`。
- 可由 `APP_TIME_ZONE` 覆盖。
- 用于「今天」、导入备注年份、pause/resume、片段完成、领域 `completedAt`。

JVM/system clock：

- Entity `createdAt/updatedAt`。
- `ApiError.timestamp`。
- 收件者 `lastUsedAt`。
- Docker entrypoint 与 Surefire 固定 `-Duser.timezone=UTC`，避免既有 SQLite epoch 解码偏移。
- 当前 GitHub Release 的 jpackage java options 没有 `-Duser.timezone=UTC`，安装版会使用主机 JVM 时区。
- 新的部署/打包入口必须继续明确处理 persistence timezone；不要把 business Clock 与 JVM timezone 混为一谈。

HTTP 与 Entity 使用无 offset 的 `LocalDateTime`。不要单独把某一层改成 UTC instant 或带 offset 格式；时间模型变更必须前后端、SQLite 兼容与既有资料一起设计。

## 18. 错误与交易

已统一处理的 RequestBody validation 与业务异常：

```json
{
  "message": "错误摘要",
  "details": ["field: 详细信息"],
  "timestamp": "..."
}
```

映射：

- `MethodArgumentNotValidException` → 400，message 为「请确认输入数据」。
- `IllegalArgumentException` → 400。
- `IllegalStateException` → 409。
- 「找不到 ID」目前也是 `IllegalArgumentException`，因此为 400，不是 404。
- 其他 JSON、数据库或未处理 runtime exception 没有自定义统一映射。

交易：

- XLSX 整次导入：单一写交易。
- 工单、片段、设置、字段设置、收件者 CRUD：service transaction。
- Email：无外层长交易；成功记录收件者使用独立新交易。

不要把资料查询、PDF 生成、SMTP 与收件者写入包进同一个长交易。

## 19. 静态资源、桌面与容器

`SpaResourceConfiguration`：

- `/assets/**`：一年 public immutable cache。
- 其他静态资源：`no-store`。
- 缺失且无扩展名的非 `/api`、非 `/error` 路径：回退 `index.html`。
- 缺失 API 或含扩展名资源：不做 SPA fallback。

桌面模式：

- `APP_DESKTOP_ENABLED=true` 才启用。
- 默认启动浏览器；`APP_DESKTOP_OPEN_BROWSER=false` 可关闭。
- URL 依据启动参数/system property/env/`.env` 的 server port 优先级解析，并加进程 launch nonce。
- 数据目录中的 `desktop-instance.lock` 保证单一实例。
- 第二次启动最多等 30 秒；已有服务就只打开浏览器。
- 支持系统托盘时提供 `Open page` 与 `Exit`。

Docker：

- 单一 backend service，前端由 jar 提供。
- Dockerfile 的 package 使用 `-DskipTests`，容器 build 不替代 `mvn test`。
- Temurin 21 JRE、UTF-8 locale、文泉驿字体。
- `QN_CALENDAR_DATA_DIR=/data`。
- business Clock 默认 `Asia/Shanghai`，JVM/TZ 固定 UTC。
- 当前没有 healthcheck、restart policy、TLS、认证或自动备份；不要在文档中暗示这些已存在。

## 20. 验证

后端测试：

```bash
cd backend
mvn test
```

完整可执行 jar（会同时构建前端）：

```bash
cd backend
mvn package
```

需要运行中整合验证时，依根目录规则使用整套 Docker Compose，不要分别启动 Vite 与 Spring Boot。

测试职责：

- `WorkOrderImportServiceTests`：表头、可配置规则、小红书待配货过滤/来源、解析、去重、重导状态保留、手动建立待排工单。
- `WorkOrderSegmentServiceTests`：片段、融合、重叠、拆分、暂停、顺延、完成、删除。
- `WorkOrderServiceTests`：待排、duration、整单清空/删除、统计。
- `WorkOrderEmailServiceTests`：周/月/统计 view model、模板、PDF、MIME。
- `WorkOrderEmailTransactionTests`：SMTP 成功后记录收件者。
- `AppSettingsServiceTests`、`ImportFieldSettingsServiceTests`、`EmailRecipientServiceTests`：基础/字段设置与收件者。
- `SpaResourceConfigurationTests`：fallback/cache。
- `QnCalendarApplicationTests`：context 与 business/persistence timezone 分离。
- `desktop/*Tests`：URL、nonce、单一实例。

当前没有 controller contract、真实 SMTP、Docker、安装器、资料升级 migration 的自动测试。新增高风险路径时，应先建立能重现规则的 service/integration test。

## 21. 不要静默扩展

- 不加入 `sort_order` 或使用者自定义排序。
- 不做自动初始排程算法。
- 不允许不同订单编号重叠。
- 不把 Email 改成纯文字清单或嵌入 JavaScript。
- 不在 SMTP/PDF 周围建立长数据库交易。
- 不新增复杂权限系统，除非有明确需求。
- 不把 Hibernate `ddl-auto=update` 描述成受版本控制的 migration。
- 不假定 DONE 可 reopen。
- 不把所有 deadline 规则简单写成「任何路径都不可超时」；手动排程受限，实际计时导致的自动顺延可产生 overdue。
