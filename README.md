# qn-calendar

XLSX 订单导入与工单排程系统。系统把订单汇入待排清单，支持在周表/月表中拖拽排程、拆分与融合片段、暂停/继续计时、完成统计，并通过 SMTP 寄送 A4 横向 PDF。

本文以当前源码、测试与运行设定为准，说明已经实现的完整功能逻辑。前后端内部架构与修改约束分别见：

- [前端架构与规则](frontend/AGENTS.md)
- [后端架构与规则](backend/AGENTS.md)
- [项目协作与 GitHub Flow](AGENTS.md)
- [未完成交接事项](hand-off-doc.md)

## 1. 系统组成

```text
浏览器
  → Vue / Router / Pinia / FullCalendar
  → 同源 /api/**
  → Spring Boot
  → SQLite
  → Thymeleaf + OpenHTMLtoPDF
  → 用户设置的 SMTP
```

技术基线：

- 前端：Vue 3.5.35、Vite 8.0.16、Vue Router 4.6.4、Pinia 3.0.4、Axios 1.17.0、FullCalendar 6.1.20。
- 后端：Java 21、Spring Boot 3.5.14、Spring Data JPA、SQLite、Apache POI、Thymeleaf、OpenHTMLtoPDF、Spring Mail。
- 生产：Maven 把 Vue build 打进 Spring Boot jar，由单一服务同时提供 SPA 与 API。
- 容器：production Compose 由单一 backend service 提供 SPA 与 API；开发 Compose 使用独立 Vite frontend 与 Spring Boot backend，SQLite 各自使用隔离的 named volume。
- 桌面：同一个 jar 可由 jpackage 制作 Windows 与 macOS 安装包。

前端有两个主要路由：

| 路径 | 功能 |
|---|---|
| `/schedule` | XLSX 导入、待排清单、周/月排程 |
| `/completed-stats` | 完工统计与订单月份筛选 |

顶栏提供全局 Email、设置与深浅色切换。

## 2. 核心资料与状态

系统实际使用八张业务/设置表：

| 表 | 用途 |
|---|---|
| `work_order` | 订单内容、预估/实际工时、状态与片段摘要 |
| `work_order_segment` | 一张工单的一个排程片段 |
| `work_order_segment_pause` | 片段的暂停/继续区间 |
| `app_setting` | singleton 基础设置与 SMTP |
| `app_setting_order_source_option` | 手动新增工单可选择的订单来源及显示顺序 |
| `email_recipient` | 常用/寄送过的收件者 |
| `import_field_alias` | XLSX canonical 字段的自定义表头别名 |
| `import_urgent_match_rule` | 自定义加急文字及完全匹配/包含匹配方式 |

工单状态：

```text
PENDING
  └─ 建立第一个片段 → SCHEDULED
       ├─ 删除最后片段 / 整单移回待排 → PENDING
       └─ 完成 → DONE
```

重要语义：

- `estimatedMinutes`：依导入金额计算的原始预估工时。
- `sourceCode`：来源设置中的稳定识别文字，例如 `QIANNIU`、`XIAOHONGSHU`、`DOUYIN`；旧资料会由原 `source` 补齐。
- `sourceName`、`sourceBadgeColor`、`sourceBadgeText`：工单保存来源显示资料；修改同一 `sourceCode` 的全局来源设置时会同步更新既有工单。
- `actualMinutes`：
  - PENDING 时是使用者在待排卡调整的工时。
  - 已排程时是所有片段长度总和。
- `scheduledStart` / `scheduledEnd`：所有片段最早开始与最晚结束的摘要。
- 同工单片段之间可以有空档，因此 `actualMinutes` 不一定等于摘要起讫跨度。
- DONE 会继续显示在日历并参与重叠检测。
- 现行 UI 没有取消完成；后端 `reopen` 也明确拒绝 DONE。

## 3. XLSX 导入

### 3.1 上传与文件范围

- 支持点击选档和拖拽上传；整个上传区域 hover 或键盘聚焦时显示 XLSX 字段说明，并先提示文件名命中来源名称或单字标签时以文件名为准；文件检核错误显示在上传区域下方。
- 前端先检查文件名副档名 `.xlsx`。
- 后端 multipart 单档与请求上限是 20MB，并由 POI 尝试解析上传的工作簿；后端不按副档名严格限定 OOXML `.xlsx`。
- 只读取第一个 sheet。
- 第一资料行必须是表头。
- 空白资料行略过；公式 cell 会先计算。
- 有效资料行与错误资料行可以同时存在：逐行解析，错误不会阻止其他有效资料行导入。
- 上传区的问号说明可用 hover 或键盘 focus 查看必填、选填字段。

导入成功后前端会：

1. 显示新增与更新笔数。
2. 并行刷新待排清单与当前可见日历；日历尚未产生 `activeRange` 时只会刷新待排。
3. 持续显示每一条错误资料行，不自动清除。

导入期间 store 会进入 loading，但目前上传按钮不会据此停用或显示 loading。

回传格式：

```json
{
  "createdCount": 10,
  "updatedCount": 3,
  "skippedCount": 2,
  "errors": [
    {
      "row": 8,
      "message": "订单编号不可为空"
    }
  ]
}
```

### 3.2 表头对应

表头会 trim、转小写，并移除空白、`_`、`-`。至少必须出现订单编号、价格、最晚发货三个 canonical 字段；即使备注中能解析发货日，也不能省略最晚发货栏。

| 资料 | 兼容表头 |
|---|---|
| 订单编号 | `订单编号`、`訂單編號`、`订单号` |
| 价格 | 订单价格、买家实付金额、价格、金额及繁体形式、`用户应付金额(元)` |
| 加急 | 加急、急件、备注标签及繁体形式、`包裹备注标记` |
| 买家留言 | 买家留言及繁体形式、`用户备注` |
| 商家备注 | 商家备注及繁体形式、`包裹备注信息` |
| 订单付款时间 | 订单付款时间、付款时间、支付时间及繁体形式 |
| 最晚发货 | 应发货时间、最晚发货日期/时间及繁体形式、`承诺发货时间` |

资料按表头名称读取，不依赖栏位顺序。

预设别名会作为初始默认值显示在「全局设置 → 字段识别设置」的同一份 InputTag 别名清单前方且不可删除；使用者可在清单尾端输入并按 Enter 或逗号新增别名，自定义别名提供删除按钮且删除前会确认。同一字段同时命中一个预设别名和一个新增别名时，新增别名优先，因此可用新增价格栏覆盖预设的 `用户应付金额(元)`；若同时命中两个预设别名或两个新增别名，整份文件会被拒绝，避免静默选错栏位。

订单来源与小红书状态：

- 先以原始 XLSX 文件名比对「基础设置」目前的订单来源名称与单字标签；任一文字被包含即视为命中，忽略英文字母大小写，并将 `小紅書` 正规化为 `小红书`。命中时保存该来源的识别文字、名称、标签颜色与单字标签。
- 文件名同时命中多个来源时整份拒绝，避免静默选错；完全没有命中时才依订单编号 fallback：`P` 加纯数字为 `XIAOHONGSHU`，其余为 `QIANNIU`。
- 小红书价格预设读取 `用户应付金额(元)`。
- 小红书只导入 `订单状态 = 待配货`；其他状态不进入工单，计入 `skippedCount`，前端会显示跳过笔数。
- 文件名已辨识为小红书时必须有 `订单状态` 表头；小红书资料的空白状态是该资料行错误。
- 已存在的订单编号不可改成另一个来源或自定义来源名称重新导入。

### 3.3 每个资料行的字段解析

订单编号：

- trim 后不可为空。
- 数据库 `order_no` 有唯一约束。

价格：

- 移除逗号、`NT$`、`¥`、`￥`、`$`。
- 不可为空或为负数；0 目前允许。

加急：

- 接受 `true`、`yes`、`y`、`1`、`是`、`加急`、`急件`。
- 文字只要包含「加急」或「急件」也视为加急。
- 「字段识别设置」可新增完全匹配或包含匹配的加急文字；例如 `红旗` 不是预设规则，只有使用者自行加入后才代表加急。

备注：

```text
买家留言：...
商家备注：...
```

- 有哪一项就保留哪一项。
- 两项都空时写入 `无任何备注`。

订单付款时间：

- 可空。
- 用于完工统计月份，以及只有日号的发货备注解析。
- 有值但格式错误时，该资料行整体失败。

### 3.4 预估工时

基础金额默认 100，可在全局设置调整。公式是：

```text
estimatedMinutes = ceil(price / estimatedHourlyBaseAmount) × 60
```

也就是先按整小时向上取整，再转换为分钟。例如：

- 250 / 100 → 3 小时 → 180 分钟。
- 250 / 200 → 2 小时 → 120 分钟。

新工单建立时：

```text
actualMinutes = estimatedMinutes
status = PENDING
```

### 3.5 最晚发货时间

来源优先级：

1. 商家备注。
2. 买家留言。
3. 应发货/最晚发货栏。

备注日期：

- 支持类似 `7/28发`、`7.28收到`、`7月28日发`。
- 月日使用 application Clock 当前年。
- 只有类似 `28号发` 或 `发28号` 时，月份来自同一资料行的订单付款时间。
- 备注无法解析时继续下一来源。

fallback 栏支持：

- ISO LocalDateTime。
- `yyyy-MM-dd HH:mm:ss`、`yyyy-MM-dd HH:mm`。
- 不补零形式与 `/` 分隔。
- `yyyy-MM-dd`、`yyyy/M/d`。
- 文字中一个或多个 `yyyy-MM-dd HH:mm[:ss]前`；多个取最早值。
- Excel date cell。

纯日期与 midnight Excel date 统一转成当天 `23:59:59`。

### 3.6 同档去重与重新导入

- 每一资料行必须完整解析成功后才进入候选资料。
- 同一份 XLSX 有相同订单编号时，最后一笔有效资料行生效。
- 后续错误资料行会出现在 `errors`，但不会覆盖前一笔有效资料行。
- 新增/更新数按唯一有效订单编号计算。
- 整次导入在单一数据库交易内。

重新导入既有订单会更新：

- 价格。
- 备注。
- 加急。
- 最晚发货。
- 订单付款时间。
- 重新计算后的预估工时。

会保留：

- 工单 ID 与订单编号。
- PENDING/SCHEDULED/DONE 状态。
- 排程片段。
- 暂停纪录。
- 完成时间与排程摘要。

`actualMinutes` 更新规则：

- SCHEDULED 或 DONE：保留。
- PENDING 且仍等于旧 `estimatedMinutes`：跟随新 estimate。
- PENDING 但已经人工调整：保留人工值。

重新导入不会重新验证既有片段是否仍早于新期限；若把已排或 DONE 工单的最晚发货改早，既有片段可以因此直接变成 overdue。

## 4. 待排工单

固定排序：

```text
latestShipTime ASC
→ urgent DESC
→ createdAt ASC
```

后端按此完整排序。`WorkOrderResponse` 不含 `createdAt`，所以前端比较器只会再次按期限与加急排序，并依 JavaScript 稳定排序保留后端同值项目的 `createdAt` 次序。系统不提供使用者手动排序。

待排卡显示：

1. 完整 `#订单编号`；卡片右上角使用来源设置中的单字标签与颜色，预设为蓝色「千」和红色「书」。
2. `¥价格` 与工时减/加按钮。
3. 红色时钟最晚发货与删除按钮。

加急工单继续使用红色外框与 Tooltip 的 `[加急]`，不再另外显示「急」文字标记。

清单填满侧栏剩余高度，左右与底部保留一致间距。

互动：

- 标题列左侧显示待排笔数，右侧「＋」打开手动新增表单。
- 手动新增提供订单来源、订单编号、买家实付金额、应发货时间、备注标签、买家留言、商家备注与订单付款时间；来源、订单编号、金额与期限以红色 `*` 标示并在前后端校验。
- 应发货时间与订单付款时间点击后直接打开浏览器日期时间选择器，并阻止键盘、贴上或拖放文字输入。
- 手动新增的来源由基础设置选项决定；系统保存识别文字、名称、标签颜色与单字标签，待排与日历共用这份显示资料。备注标签套用字段识别设置中的加急文字规则；重复订单编号会被拒绝，不覆盖原工单。
- 每次以 15 分钟调整工时，控制器最低显示 15 分钟。
- `PATCH /duration` 只允许 PENDING，值至少 15 且必须是 15 分钟倍数。
- 调整的是 `actualMinutes`，不修改原始 `estimatedMinutes`。
- 删除前确认；只有 PENDING 可删，成功后刷新清单。
- 卡片 hover/focus 显示订单、工时、状态、期限、价格与备注 tooltip。
- 短按复制不含 `#` 的订单编号；移动超过 6px 视为拖拽，不触发复制。
- 复制优先使用 Clipboard API，失败时使用隐藏 textarea fallback。
- 成功提示固定在顶栏中央 3 秒，不推动既有版面。
- 聚焦卡片时，周视图显示该工单最晚发货红线。

## 5. 日历与手动排程

### 5.1 视图与保存状态

- 支持连续 7 天周视图与月视图。
- 首次没有保存日期时，周视图从今天开始；之后恢复上次日期，不保证第一栏仍是今天。
- 视图切换以来源视图实际显示的第一天为日期基准；「允许过去」关闭时，再按目标视图校正边界（周视图下限为今天、月视图下限为当前月）。越界时只校正到下限一次，已在下限继续向过去导航不会触发重绘。
- 取消勾选「允许过去」时会立即重置当前日期：周视图以今天为第一天，月视图显示当前月。
- 周视图的水平滚轮/触控板手势按天移动，垂直滚动仍用于浏览时间轴；左右方向键也按天移动并支持按住连续跳转。
- 月视图的水平或垂直滚轮/触控板手势按月移动，持续滚动可连续逐月切换；左右方向键按月移动并忽略长按重复事件。
- 日历区域会隔离水平 overscroll，避免触控板或滚轮误触浏览器上一页/下一页。
- 输入框、下拉框与可编辑区域内的左右方向键不触发日历导航。
- 鼠标或触控点击按钮、checkbox、radio、switch 等操作型控件后会释放焦点；使用 Tab 的键盘焦点仍保留。
- 周视图格线每 30 分钟显示，拖拽/resize snap 为 15 分钟。
- 月视图拖到日期后，实际排程开始固定为该日 `09:00:00`。
- 月视图可拖动但不可 resize；周视图可上下 resize。
- 周视图加载或切换区间后：
  - 切换期间保留当前时间轴位置，避免先跳回默认时间再二次定位。
  - 有工单时滚到区间内最早可见工单。
  - 没有工单时使用全局「周表默认开始时间」。
- 周表头显示「日号 + 星期」，月表头只显示星期。
- 日期使用 `yyyy-MM-dd`，日期时间使用 `yyyy-MM-dd HH:mm:ss`，24 小时制。

浏览器保存：

| Key | 内容 |
|---|---|
| `qn-calendar-view` | 周/月视图 |
| `qn-calendar-date` | 当前基准日 |
| `qn-calendar-allow-past-scheduling` | 测试用「允许过去」 |

「允许过去」默认关闭，只是前端测试开关；后端没有额外的过去日期限制。

### 5.2 待排拖入、移动与 resize

每次手动操作流程：

1. 前端把起讫向上正规化到 15 分钟边界。
2. 默认检查不可早于浏览器今天。
3. 检查结束不可晚于最晚发货。
4. 检查与不同工单的可见事件重叠。
5. 发 API。
6. 后端对全部 SCHEDULED/DONE 片段做最终验证。
7. 成功后刷新服务端事件；失败则 revert。

后端一般手动建立或修改片段必须同时满足：

```text
end > start
start/end 都在 15 分钟边界
duration 是 15 分钟倍数
end <= latestShipTime
不同工单不可重叠
```

重叠采用半开区间，因此首尾相接允许。

这些片段 create/update/split/delete API 没有按 PENDING/SCHEDULED/DONE 统一设状态 gate；例如调用者仍可对 DONE 工单建立或拆分片段，normalize 后状态会保持 DONE。现行 UI 隐藏 DONE 的建立/拆分操作不等于后端禁止。

### 5.3 重叠时自动贴齐

如果落点与不同工单重叠，前端不会立即放弃拖拽意图，而会：

1. 先确认原落点本身没有违反今天下限或最晚发货；违反时直接拒绝。
2. 沿冲突链寻找前方最近空档。
3. 沿冲突链寻找后方最近空档。
4. 排除早于今天或超过期限的候选。
5. 选择位移较小者。
6. 都不可用时才 revert。

DONE 事件也参与阻挡。相同工单不视为不同工单冲突，会交给后端融合。

拖拽/resize 时会显示候选开始、结束、工时和拒绝原因；API 失败时：

- 外部 drop：移除临时事件并 revert。
- 日历 move/resize：`info.revert()`。
- 拖出日历失败：重新查询可见区间。

### 5.4 事件卡、聚焦与状态

事件卡固定显示：

- 来源前缀与订单编号，例如 `[千] 订单编号`、`[书] 订单编号`。
- `HH:mm~HH:mm`。
- 整张工单总排程工时。
- 「最晚发货」日期。
- 「最晚发货」秒级时间。

tooltip 另显示：

- 当前片段起讫。
- 整单总排程与暂停时长。
- 完成/暂停/未完成状态。
- 价格与订单备注。

视觉状态可叠加：

- 加急。
- 完成淡化。
- 逾期红色。
- 暂停。
- 同订单选中外框。
- 有暂停历史的 resize 限制。

点击任一片段：

- 短按复制订单编号。
- 同工单全部可见片段同时加外框。
- 周视图显示期限红线。
- 点击空白处清除聚焦。

DONE 卡不显示完成、拆分或暂停按钮，也没有取消完成入口。当前 DONE 片段仍可拖动，周视图也可 resize；后端会保持 DONE，并继续应用一般期限与重叠验证。

## 6. 多片段、融合、拆分与移出日历

### 6.1 建立与自动融合

同一工单可有多个片段，分布在不同天或时段。

一般建立或更新片段后，后端会按开始时间排序；相邻或重叠片段自动融合：

- 保留较早片段。
- 结束取两者较晚值。
- 被融合片段的暂停纪录迁移到保留片段。
- 重新计算摘要与总分钟。

### 6.2 显式拆分

- 所有非 DONE 事件都会显示拆分按钮；不足 30 分钟时点击会显示错误，至少 30 分钟才能拆分。
- 前端选择最接近中点的 15 分钟边界。
- 拆分点必须严格位于片段内。
- 正在暂停的片段不可拆。
- 今日有暂停历史时，拆分点不可早于最后暂停时间向上取整后的边界。
- split 是刻意例外：刚拆出的两个相邻片段会保留。
- 后续建立/移动/resize 触发 normalize 时，若两段仍相邻，可能再次融合。

### 6.3 拖出日历

事件拖出整个日历范围后会立即从画面移除，不播放回弹。

后端按被拖出片段的原开始日期与 application Clock 今天判断：

- 今日片段：
  - 删除这张工单全部暂停。
  - 删除全部片段。
  - 清空排程与完成时间。
  - 回 `PENDING`。
  - `actualMinutes` 恢复 `estimatedMinutes`。
- 非今日片段：
  - 只删指定片段及其暂停。
  - 还有其他片段就保持已排。
  - 最后一段删除后才回 PENDING。

前端 optimistic remove 对「今天」固定按 `Asia/Shanghai` 判断；最终资料仍以后端 `APP_TIME_ZONE` 规则为准。

后端另保留整单 `PATCH /{id}/unschedule`，可不看日期直接清除全部片段/暂停；现行前端没有使用这个端点。

## 7. 暂停、继续、完成与自动顺延

### 7.1 暂停

片段同时满足以下条件才可暂停：

- 工单未 DONE。
- 片段开始日期是 application Clock 的今天。
- 当前时间不早于片段开始。
- 当前没有开放中的 pause。

前端按钮使用浏览器今天判断，且不要求当前时间仍早于原结束；后端是最终权威。

每次暂停建立：

```text
pausedAt = 当前业务时间
resumedAt = null
```

允许同一片段多次暂停/继续。

### 7.2 继续

- 必须存在开放 pause。
- DONE 不可继续。
- 继续时间不可早于暂停时间。
- 可以跨日继续。
- 每个暂停区间用整分钟计算，不足一分钟的秒数截断。
- 日历、完工统计及 Email/PDF 显示的暂停时长，只累计暂停区间与同一工单各排程片段重叠的时间；拆分片段之间的空档及排程范围外时间不计入。
- 开放中的 pause 以当前时间为暂定结束；工单完成后改以 `completedAt` 为结束，再与所有排程片段取交集。

### 7.3 暂停历史对移动/resize 的限制

今日、未完成且已经有任一暂停纪录的片段：

- 仍可保持原长度整体移动。
- 移动后只保留落在新区间内的 pause：
  - `pausedAt` 必须在新区间。
  - 已关闭 pause 的 `resumedAt` 也必须在新区间。
  - 开放 pause 只要求 `pausedAt` 在新区间。
- resize 固定开始时间，只能延后结束；不可缩短，也不可从上边缘修改。
- 融合时有效 pause 会迁移到保留片段。

### 7.4 超过排定结束与连锁顺延

如果暂停或继续发生在原结束之后：

1. 当前片段结束向上取到下一个 15 分钟边界。
2. 查询 `scheduledEnd > 原结束` 的其他 SCHEDULED/DONE 候选，并按开始时间排序。
3. 从 cursor 开始依序处理相交候选；遇到第一张 `start >= cursor` 的片段就停止，形成的冲突链逐张往后平移。
4. 保持候选原 duration，并把新结束向上取 15 分钟边界。
5. 同步所有受影响工单摘要。

这条「实际计时延长」路径不会重新检查各工单最晚发货，因此允许自动形成 overdue；逾期事件会标红。它不同于手动拖拽/resize，后者仍不可超过期限。

顺延只修改候选片段起讫，不同步平移候选片段已有的 pause 时间戳。

系统没有自动初始排程算法；这里只会延续用户已经开始的实际计时并推开冲突链。

### 7.5 完成

现行 UI 调用片段完成 API：

```http
PATCH /api/work-orders/segments/{segmentId}/done
```

逻辑：

1. 如果仍在暂停，以完成时间关闭最后开放 pause。
2. 完成时间与片段开始同日、晚于开始且不等于原结束时，把片段结束直接改成完成时间：
   - 完成较早时会缩短。
   - 完成较晚时会延长并顺延后续冲突链。
   - 保留完成时间的秒数，不向上取 15 分钟。
3. 不同日完成，或完成时间不晚于开始时，不改原片段结束。
4. 把整张工单设为 DONE，记录 `completedAt`。

完成产生的延长与顺延也不重新检查最晚发货，因此可能 overdue。

后端另有直接整单 done 端点，只改状态与完成时间，不调整片段、不关闭 pause；现行 UI 不使用。`reopen` 虽保留端点与前端 client，但当前后端拒绝 DONE，不能取消完成。

## 8. 完工统计

统计只列 DONE，按下列顺序：

```text
有 orderTime 的在前
→ orderTime DESC
→ latestShipTime ASC
→ createdAt ASC
```

计算：

```text
scheduledTotalMinutes = 所有片段分钟总和
pausedMinutes = 所有暂停区间与同工单排程片段的重叠分钟总和
actualTotalMinutes = max(0, scheduledTotalMinutes - pausedMinutes)
deltaMinutes = actualTotalMinutes - estimatedMinutes
hourlyRate = price × 60 / actualTotalMinutes
```

- 时薪四舍五入到两位；实际工时为 0 时显示 `-`。
- 工时差为 0 显示「符合预期」，正数「超出」，负数「提前」。
- 表格字段：订单编号、订单备注、订单价格、预估工时、实际工时、暂停时长、工时差、时薪。
- 订单月份筛选依据 `orderTime`，不是 `completedAt`。
- 页面一次读取全部统计，再在浏览器按 `yyyy-MM` 筛选。
- 没有 `orderTime` 的 DONE 会出现在「全部」，不会出现在某一月份。
- 统计页用 `h/m`，待排与日历用「小时/分钟」。

## 9. 全局设置

设置 Dialog 有四个 tab：Email 收件者、Email 寄件者、基础设置、字段识别设置。标题、四个 tab 与关闭按钮位于同一横排；宽度不足时 tab 区可水平滚动，滚动期间才显示滚动条。默认打开「Email 收件者」；打开状态和当前 tab 通过 URL query 保存。

### 9.1 基础设置

```json
{
  "estimatedHourlyBaseAmount": 100,
  "weekViewDefaultStartTime": "06:00",
  "orderSourceOptions": [
    { "name": "千牛", "identifier": "QIANNIU", "badgeColor": "#218BFF", "badgeText": "千" },
    { "name": "小红书", "identifier": "XIAOHONGSHU", "badgeColor": "#FF5C5C", "badgeText": "书" }
  ]
}
```

- 基础金额默认 100，必须大于 0、整数部分最多 12 位且小数最多两位。
- 周表默认开始为 `06:00`，必须落在半小时边界。
- 基础金额与周表默认开始时间在 `change` 时分别自动保存；成功后在对应字段标题右侧显示 5 秒「已保存」，不提供基础设置总保存按钮。
- 订单来源选项默认千牛、小红书，以横跨两栏的 InputTag 新增；来源名称、唯一识别文字、十六进制标签颜色与单一文字标签全部必填。新增标签后自动聚焦空白识别文字；若尚未填写识别文字便重新聚焦「继续添加」，会先确认是否放弃，确认后移除该未保存标签，取消则回到识别文字。点击既有标签可编辑，并使用编辑区内的保存按钮提交。保存后会依 `sourceCode` 同步既有待排与日历工单并立即刷新画面。必须保留 1–20 个、名称和识别文字不可重复。
- 删除已保存来源前会先读取受影响工单数；存在工单时，确认文案明确说明这些工单与排程记录会永久删除。确认后在单一交易内删除暂停、片段、工单与来源设置；删除提示使用红色，重新读取设置时不会恢复已删来源。

### 9.2 字段识别设置

- 通过 `GET/PUT /api/settings/import-fields` 读取与保存完整设置快照。
- 七个固定字段会显示必填/选填状态；每个字段只显示一份「别名」清单，标准字段名本身不重复显示，不可删除的其他预设别名在前，使用者新增且可删除的别名接在后方。新增并自动保存成功后会清空内容并继续聚焦同一输入框，方便连续添加。
- 自定义别名命中时优先于同字段的系统别名，删除自定义别名即可恢复预设读取栏位。
- 备注标签字段可另外维护「表示加急的文字」，匹配方式为完全匹配或包含文字。
- 自定义别名与加急规则保存在独立资料表；保存时会检查标准化后的跨字段冲突、系统别名冲突、空值、重复与长度。
- 「订单付款时间」只接受付款/支付时间语义；订单时间、下单时间、下单日期的繁简别名不会显示或参与导入，后端会删除数据库中遗留于该字段的同名自定义别名并拒绝重新加入。
- 新增或确认删除别名、加急文字后会立即自动保存；成功提示显示在对应标准字段名右侧并于 5 秒后消失。删除前确认文案会明确指出字段或规则内容，保存失败则恢复服务器上的原设置。
- 字段识别设置不再提供底部保存按钮。
- XLSX 每次导入开始时读取一次设置快照，同一次导入不会混用两版设置。

### 9.3 Email 寄件者

保存：

- 寄件 Email。
- SMTP server。
- SMTP port。
- 加密方式 `NONE` / `SSL` / `STARTTLS`。
- SMTP 授权码。

规则：

- 初次设置所有字段都必须齐全。
- sender Email 最多 320 字、SMTP host 最多 255 字、授权码最多 1024 字。
- 后端允许 port 1–65535；前端目前只提供 465、587。
- 已设置时摘要显示遮罩 Email。
- 编辑时回填完整 sender Email、host、port、security。
- 授权码输入默认遮罩，可切换显示。
- `••••••••` 代表沿用旧授权码，前端送 `null`。
- 后端不会通过查询 API 回传真实授权码。
- 当前 API 不能以空值清除旧授权码。
- 授权码以明文保存在本机 SQLite；数据库与备份需要当作敏感资料保护。

### 9.4 Email 收件者

支持新增、行内编辑、删除：

- 手动管理时姓名、Email 都必填。
- 姓名最多 120 字；Email 最多 320 字。
- Email trim、转小写并忽略大小写防重。
- 删除前确认。

排序：

```text
lastUsedAt DESC
→ usageCount DESC
→ name ASC
→ email ASC
```

寄信成功后才更新使用纪录：

- 已存在：`usageCount + 1`，更新 `lastUsedAt`。
- 不存在：自动建立，姓名可暂时为空。
- 同一批重复 Email normalize 后只记录一次。
- 使用独立短交易写入，不在 SMTP 等待期间持有长交易。

SMTP 成功后的收件者写入仍是独立数据库操作；若它本身失败，API 仍可能回错误，因此客户端不可盲目自动重寄。

## 10. Email Dialog 与 PDF

### 10.1 收件人与表单

- 打开时刷新 SMTP、常用收件者与完工月份。
- 收件人为 tags + combobox：
  - 用姓名或 Email 搜索。
  - 已选项从建议中排除。
  - 支持上下键、Enter、逗号、Backspace、Escape。
  - 可直接输入不在常用清单的合法 Email。
  - Email 转小写并去重。
- 当前关闭再打开 Dialog 会保留同一组件实例中的已选收件者。
- SMTP 未配置时禁止发送，并提供跳转寄件者设置。
- 发送成功显示 5 秒提示，Dialog 不自动关闭。
- 主题只读并自动产生；附件名由后端根据报表类型与日期产生，不信任主题文字。
- 主题在前后端都不可为空。
- 发送或初始化失败写入排程 store 的全局错误；Dialog 本身没有错误区，因此现有错误会显示在遮罩下方的排程页或完工统计页。

### 10.2 报表类型

WEEK：

- 从周视图打开时默认选择 WEEK 并使用当前周范围；从月视图打开时默认选择 MONTH，但若再切换到 WEEK，日期字段沿用当前焦点所在周。
- 起讫日期必填，结束不可早于开始。
- 范围超过 7 天时，每 7 天一节并换页。

MONTH：

- 选择月份。
- 后端强制使用该月第一天到最后一天。

COMPLETED_STATS：

- 默认沿用完工统计页订单月份。
- 月份可空；空值发送 `dateFrom = null`、`dateTo = null`，代表全部。
- 有月份时按 `orderTime` 筛选完整月份。

自动主题：

```text
周表 - 开始日期 - 结束日期
月表 - yyyy-MM
完工统计表 - yyyy-MM
完工统计表 - 全部
```

### 10.3 PDF 内容

共同规则：

- Thymeleaf HTML 只用于 OpenHTMLtoPDF 渲染。
- PDF 固定 A4 横向 297×210mm，模板页边距 8mm。
- 邮件正文为空，只发送 PDF 附件。
- Docker 内置文泉驿字体；本机按 macOS/Windows/Linux 候选路径寻找中文字体。
- 中文附件名同时写 UTF-8 RFC 5987 `filename*` 与 MIME encoded-word `filename`。

周表：

- 顶部直接从时间轴表格开始。
- 左侧小时刻度，上方日期/星期。
- 显示订单编号、加急、完成淡化、开始/结束、最晚发货与备注。
- 跨日片段按每天可见区间裁切。
- 每一页按该 7 天最早开始向下取整小时、最晚结束向上取整小时裁掉空白。
- 没有工单时使用 09:00–18:00。

月表：

- 从周日开始排列。
- 显示订单编号、加急、完成淡化、开始/结束与最晚发货。
- 若月底前已经没有后续工单，会在最后有资料的周结束并显示「之后暂时没有排工单」。

完工统计：

- 字段与前端统计表一致。
- 月份标题或「全部」及笔数。
- 铺满可打印宽度。

附件名：

```text
周表 - yyyy-MM-dd - yyyy-MM-dd.pdf
月表 - yyyy-MM.pdf
完工统计表 - yyyy-MM.pdf
完工统计表 - 全部.pdf
```

### 10.4 发送顺序与 timeout

```text
验证 request
→ 短查询取得资料
→ 交易外生成 HTML/PDF
→ 动态建立 SMTP client
→ SMTP 发送
→ 成功后独立交易记录收件者
```

前端 Axios timeout 是 20 秒，当前 JavaMail 未设置 connect/read/write timeout。慢 SMTP 可能让浏览器先显示 timeout，但后端之后仍完成发送；系统目前没有自动重试，使用者重送前应先确认邮箱。

## 11. 表单、提示、主题与响应式

表单验证：

- 自定义表单使用 `novalidate`。
- 提交时一次验证画面全部必填字段，不在第一个错误停止。
- 每个错误显示在对应 label 旁。
- 控件同步设置 `aria-invalid`、`aria-describedby`。
- invalid 使用红框；focus ring 仍使用蓝色主题。
- 字段错误不会因输入、blur、focus 或计时自动消失。
- 只有取消、切 tab/子画面、提交成功并重置，或下次重新打开等明确动作会清除。
- 无法定位到单一字段的 API/网络错误才使用表单层或全局提示。

提示：

- work-order store 的全局错误、成功与状态提示：5 秒。UIUX 颜色原则为删除结果与检核/操作错误使用红色，其余一般成功或状态提示使用主题蓝色。
- 复制订单编号：3 秒。
- 重复触发会重置计时。
- XLSX 逐行错误持续显示。

主题与布局：

- 系统可见中文文案使用简体中文；只有 XLSX 表头 alias 保留繁体兼容。
- CSS variables 驱动深色/浅色，默认深色。
- 主题保存在 `qn-calendar-theme`。
- 主色为蓝色；加急、完成、逾期、暂停有独立但克制的状态。
- 应用限制在 `100dvh`，清单、日历、表格、Dialog 使用内部滚动。
- 980px 以下待排与日历上下堆叠；有足够宽度时待排卡可多栏。
- 620px 以下双栏表单改为单栏。
- tooltip 支持鼠标 hover、键盘 focus、Escape，并依实际尺寸翻转/限制在 viewport 内；鼠标或焦点进入完成、拆分、暂停/继续按钮时会隐藏，避免 Windows Firefox 中视觉上遮住操作区。

## 12. API 总览

成功状态通常为 200；手动新增工单与收件者为 201；删除待排、删除收件者、发送 Email 为 204。

### 12.1 工单

| Method | Path | 功能 |
|---|---|---|
| `POST` | `/api/work-orders/import` | XLSX 导入 |
| `POST` | `/api/work-orders` | 手动新增待排工单 |
| `GET` | `/api/work-orders/pending` | 待排 |
| `GET` | `/api/work-orders/calendar?dateFrom&dateTo` | 日历片段 |
| `GET` | `/api/work-orders/statistics/completed` | 完工统计 |
| `PATCH` | `/api/work-orders/{id}/schedule` | 新增片段 |
| `PATCH` | `/api/work-orders/segments/{segmentId}` | 移动/resize |
| `DELETE` | `/api/work-orders/segments/{segmentId}` | 拖出日历 |
| `POST` | `/api/work-orders/segments/{segmentId}/split` | 拆分 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/done` | 现行完成流程 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/pause` | 暂停 |
| `PATCH` | `/api/work-orders/segments/{segmentId}/resume` | 继续 |
| `PATCH` | `/api/work-orders/{id}/duration` | 待排工时 |
| `DELETE` | `/api/work-orders/{id}` | 删除待排 |
| `POST` | `/api/work-orders/schedule-email` | PDF Email |

后端保留但现行 UI 不使用：

| Method | Path | 当前行为 |
|---|---|---|
| `PATCH` | `/api/work-orders/{id}/unschedule` | 整单清除片段/暂停 |
| `PATCH` | `/api/work-orders/{id}/done` | 只改整单状态与完成时间 |
| `PATCH` | `/api/work-orders/{id}/reopen` | DONE 会被拒绝 |

### 12.2 设置与收件者

| Method | Path | 功能 |
|---|---|---|
| `GET` | `/api/settings` | 读取设置 |
| `PUT` | `/api/settings` | 保存基础设置 |
| `GET` | `/api/settings/order-sources/{identifier}/deletion-impact` | 读取删除来源会影响的工单数 |
| `DELETE` | `/api/settings/order-sources/{identifier}` | 删除来源及该来源全部工单与排程记录 |
| `PUT` | `/api/settings/email-sender` | 保存 SMTP |
| `GET` | `/api/settings/import-fields` | 读取 XLSX 字段与加急判定设置 |
| `PUT` | `/api/settings/import-fields` | 替换 XLSX 自定义字段与加急判定设置 |
| `GET` | `/api/email-recipients` | 收件者列表 |
| `POST` | `/api/email-recipients` | 新增 |
| `PUT` | `/api/email-recipients/{id}` | 编辑 |
| `DELETE` | `/api/email-recipients/{id}` | 删除 |

已由 `ApiExceptionHandler` 统一处理的 RequestBody validation 与业务异常使用：

```json
{
  "message": "错误摘要",
  "details": ["field: 详细信息"],
  "timestamp": "..."
}
```

状态：

- Bean Validation、`IllegalArgumentException` → 400。
- `IllegalStateException` → 409。
- 当前「找不到 ID」也使用 400，不是 404。
- 前端统一正规化为 `{ message, status, details }`。

缺少 query parameter、型别转换、malformed JSON、multipart 超限、404/405 与未捕获异常不在这三个 handler 内，仍可能采用 Spring Boot 默认错误结构。

## 13. 时区、编码与持久化

业务时间：

- application Clock 默认 `Asia/Shanghai`。
- 可用 `APP_TIME_ZONE` 覆盖。
- 用于「今天」、导入备注年份、暂停/继续、片段完成。

持久化运行时：

- Docker、后端测试与上方 README 手动启动命令固定 JVM `UTC`，避免既有 SQLite epoch 资料整体偏移。
- 当前 jpackage 安装版没有传 `-Duser.timezone=UTC`，会使用安装主机的 JVM 系统时区；这与 Docker/测试入口不同。
- Entity 建立/更新时间、API error timestamp、收件者 last-used 使用 JVM system clock，不是 application Clock。
- API 与 Entity 使用无 offset 的 `LocalDateTime`。

前端还会用浏览器 `Date` 判断今天与格式化 API 时间，拖出日历的 optimistic 判断则固定 `Asia/Shanghai`。若客户端不在 UTC+8 或覆盖 `APP_TIME_ZONE`，前后端日界线可能不同。

编码：

- 源码、模板、HTTP、Email/PDF 与附件名使用 UTF-8。
- Docker设 UTF-8 locale，并安装中文字体。

Schema：

- 正式环境是 Hibernate `ddl-auto=update`。
- 测试是 `create-drop`。
- 当前没有受版本控制的 migration。
- 对实体字段或关系的修改必须先考虑现有 SQLite 兼容与备份，不可把 Hibernate update 当作完整 migration 策略。

安全与部署边界：

- 当前没有登入、权限、应用层 TLS 或 CORS 配置。
- SMTP 授权码明文存于 SQLite。
- Docker Compose 没有 healthcheck、restart policy 或自动备份。
- 对外暴露前需要由部署层补网络访问控制、TLS 与备份。

## 14. 构建与运行

### 14.1 前端 production build

```bash
cd frontend
npm run build
```

### 14.2 后端测试

```bash
cd backend
mvn test
```

### 14.3 包含前端的可执行 jar

```bash
cd backend
mvn package
```

Maven 会下载项目指定 Node、执行 `npm ci`、构建 Vue，并输出 executable jar。

以桌面模式启动：

```bash
cd backend
java \
  -Dapp.desktop.enabled=true \
  -Djava.awt.headless=false \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC \
  -jar target/qn-calendar-backend-0.1.0.jar
```

### 14.4 开发环境 Docker Compose

```bash
cp .env.example .env
docker compose -f docker-compose.dev.yml up --build
```

打开实际 `FRONTEND_PORT` 对应的网址，默认是 `http://localhost:5174`。浏览器请求同源 `/api/**`，由 Vite 代理到 Compose 网络内的 `backend:8080`，因此开发环境不需要 CORS。

`.env`：

```properties
SERVER_PORT=8080
FRONTEND_PORT=5174
APP_TIME_ZONE=Asia/Shanghai
```

开发环境：

- frontend 使用 Node 22.12 与 Vite HMR，修改 `frontend/src` 后不重建 Maven 或 backend image。
- backend 使用 `mvn spring-boot:run`，不会进入 Maven `prepare-package`，因此不会安装 Node、执行 `npm ci` 或构建 Vue。
- 修改 `backend/src` 后可执行 `docker compose -f docker-compose.dev.yml restart backend`，重新编译并启动 API；修改 `pom.xml` 才需要重建 backend image。
- backend 的 host port 使用 `SERVER_PORT`，容器内固定为 `8080`；frontend 的 host port 使用 `FRONTEND_PORT`，容器内固定为 `5174`。
- 开发 Compose 的 project name 固定为 `qn-calendar-dev`，容器、image、network 与 volume 都不会覆盖 production Compose 的同名资源。
- `APP_DESKTOP_ENABLED=false`。
- SQLite 位于 `/data/qn-calendar.db`。
- named volume 是 `qn-calendar-dev-data`，不会与 production 资料共用。
- 业务时区默认北京，JVM/TZ 固定 UTC。

开发 Compose 只绑定到 `127.0.0.1`。若 `5174` 或 `8080` 已被占用，请先停止冲突服务或调整 `.env`，不要同时让开发与 production Compose 使用相同 host port。

不要执行 `docker compose -f docker-compose.dev.yml down -v`，除非明确要删除开发 SQLite volume。

### 14.5 Production Docker Compose

```bash
docker compose up --build
```

打开实际 `SERVER_PORT` 对应的网址，默认是 `http://localhost:8080`。

Production：

- 前后端由同一个 Spring Boot service 提供。
- named volume 是 `qn-calendar-data`。
- Docker 固定忽略 `.env` 中的 `QN_CALENDAR_DATA_DIR`。
- Dockerfile 以独立 Node stage 缓存 `npm ci` 与 Vue build，再由 Maven stage 封装单一 executable jar；只修改 Java 时可重用完整前端 build layer。
- Maven package 使用 `-DskipTests`，容器 build 不能替代先执行 `mvn test`。

不要执行 `docker compose down -v`，除非明确要删除 SQLite volume。

### 14.6 本机资料目录

默认：

```text
~/.qn-calendar/qn-calendar.db
```

覆盖时必须在启动进程前设真实环境变量或 JVM property：

```bash
QN_CALENDAR_DATA_DIR=/absolute/path/to/qn-calendar-data \
java -Duser.timezone=UTC -jar target/qn-calendar-backend-0.1.0.jar
```

只写入 `.env` 不会影响启动前的数据目录解析。

## 15. SPA、桌面与发布

### 15.1 SPA 静态资源

- `/assets/**` 使用一年 public immutable cache。
- `index.html`、favicon、SPA fallback 使用 `no-store`。
- 缺失且无扩展名的非 API 路径回退 `index.html`。
- `/api/**`、`/error/**` 或缺失的有扩展名资源不会回退。

### 15.2 桌面运行

启用桌面模式后：

- 默认自动打开带本进程 launch nonce 的本地 URL；设 `APP_DESKTOP_OPEN_BROWSER=false` 可关闭。
- 启动前解析自订 port 的优先序是 `--server.port`、JVM `server.port` / `SERVER_PORT`、环境变量 `SERVER_PORT`、当前或父目录 `.env`，最后才是默认 8080；启动后以实际 `local.server.port` 为准。
- 数据目录同时保存 `desktop-instance.lock`。
- 第二次启动不会建立第二个后端；它最多等待现有服务 30 秒，然后打开既有页面。
- 如果前一实例在准备完成前退出，新实例可取得 lock 接手启动。
- 支持系统托盘时提供 `Open page` 与 `Exit`。
- 无桌面/托盘能力的环境会安全略过。

### 15.3 GitHub Release

推送 `v*` tag 后：

1. Ubuntu runner 构建包含前端的 jar。
2. Windows runner 用 jpackage + WiX 产生 `.exe`。
3. macOS runner 产生 `.dmg` 与 `.pkg`。
4. GitHub Release 发布安装档；jar 只作为 job 间产物，不会附在 Release。

Ubuntu build job 使用 `setup-node` 的 npm download cache 与 `setup-java` 的 Maven dependency cache。Maven 先准备 backend resources，Vue 再独立构建到 `backend/target/classes/static`，最后由 Maven 跳过 frontend-maven-plugin 的 Node/npm goal 并封装 jar；workflow 会检查 jar 内同时存在 `static/index.html` 与 JavaScript asset，避免产生缺少前端的桌面安装包。

jpackage 必须在目标平台原生执行，不能在单一 runner 跨平台产生 Windows 与 macOS 安装档。

发布 tag 必须指向已经进入 `main` 的 commit。workflow 的打包步骤使用 `-DskipTests`，因此 tag 前必须先完成测试。

jpackage installer 版本第一段必须为正整数；`v0.x.x` tag 会在安装包内部映射成对应的 `1.x.x`，Release tag 与安装档名称仍保留原版本。私有 repository 的 Release 仍受 GitHub 存取权限限制。

### 15.4 Windows 安装、升级与卸载

- 安装档名为 `QnCalendar-Setup-<releaseVersion>.exe`；它是内嵌 WiX MSI 的 jpackage 安装器入口，使用者不需要另外取得 MSI。
- 安装器使用简体中文环境，可选择程序安装目录；桌面快捷方式提示默认勾选，开始菜单入口保留。
- 安装器不提供资料库目录选择；资料仍固定使用使用者家目录下的 `.qn-calendar`，除非在启动进程外另行覆盖环境变量。
- 下载的安装档不会自动删除，安装后可手动删除。
- 固定 `win-upgrade-uuid` 让后续版本被识别为同一应用。
- 升级会先移除旧版程序目录，再安装新版；不要把数据库放在安装目录。
- 升级、修复或卸载发现应用运行时，会请求正常结束并等待最多 30 秒。
- 若仍未退出，安装会停止并提示从系统托盘选择 `Exit`，不会强制 `taskkill /F`。

### 15.5 macOS 与用户资料

- `.dmg` 用于拖入「应用程序」；更新时替换旧 App。
- `.pkg` 使用系统安装器覆盖。
- 删除 `.app` 即可移除 DMG 安装的应用本体；卸载应用本体不会删除资料库。

Windows/macOS 的数据库都放在使用者家目录，不在安装目录。卸载、覆盖安装、重新安装不会删除：

```text
~/.qn-calendar/qn-calendar.db
```

彻底清除资料需要使用者明确手动删除该目录。

## 16. 验证范围与当前边界

后端已有自动测试覆盖：

- application context 与 business/persistence timezone。
- XLSX 表头、可配置别名/加急规则、小红书待配货过滤与来源、日期、去重、重导状态保留。
- 待排排序与工时。
- 片段、融合、重叠、拆分、暂停、继续、自动顺延、完成、移出日历。
- 完工统计。
- 基础设置、SMTP 摘要、收件者。
- 周/月/完工统计 PDF、横向页数与 UTF-8 MIME。
- Email 成功/失败与收件者交易顺序。
- SPA fallback/cache。
- 桌面 URL、nonce 与单一实例。

当前没有：

- 前端 unit/component/E2E 测试。
- controller API contract 测试。
- 真实 SMTP 自动测试。
- Docker、jpackage/WiX 自动行为测试。
- 受版本控制的数据库 migration/升级测试。

因此：

- `npm run build` 只能证明前端可编译，不等于互动行为已验证。
- `mvn test` 不会验证真实邮箱与安装器。
- 需要运行中整合验证时，依项目规则使用 `docker-compose.dev.yml` 启动完整开发环境，不要在主机上分别启动前后端开发服务器；正式 JAR／静态资源回归才重建 production Compose。

已知前端标记边界：`frontend/index.html` 目前仍声明 `lang="zh-Hant"`，与现行简体中文文案规则不一致。

当前 `hand-off-doc.md` 记录为「暂无未完成事项」。上述边界是现有实现范围，不代表已经建立对应自动测试或生产基础设施。
