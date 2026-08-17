# frontend/AGENTS.md

本文件记录前端当前架构、模块边界与后续修改必须保持的约束。根目录协作规则、文案/编码要求与 GitHub Flow 请看 [`../AGENTS.md`](../AGENTS.md)；完整业务逻辑请看 [`../README.md`](../README.md)。

## 1. 技术基线

- JavaScript + Vue 3.5.35，统一使用 Composition API 与 `<script setup>`。
- Vite 8.0.16；Vue Router 4.6.4；Pinia 3.0.4。
- Axios 1.17.0；FullCalendar 6.1.20；`@lucide/vue` 1.17.0。
- `src/main.js` 的启动顺序是 `createApp` → Pinia → Router → `#app`。
- `src/assets/base.css` 是唯一全局样式入口。
- `vite.config.js` 的开发端口是 `5174`，`/api` 默认代理到 `http://localhost:8080`；开发 Compose 通过 `API_PROXY_TARGET=http://backend:8080` 覆盖容器内目标。
- Axios 默认同源，也可通过 `VITE_API_BASE_URL` 指定基址；统一 timeout 为 20 秒。

不要静默升级框架或套件主版本。版本变更必须同时核对 `package.json`、`package-lock.json`、Maven 前端构建与生产静态资源打包。

## 2. 总体架构

前端采用简单的单向数据流：

```text
用户操作
  → View / Component
  → Pinia action
  → src/api client
  → 共用 Axios instance
  → Spring Boot /api/**
  → Pinia state
  → View / Component 重新渲染
```

边界规则：

- 组件不可直接调用 Axios；HTTP 只能放在 `src/api/`。
- 跨画面、需要刷新或会被多个组件消费的服务端状态放在 Pinia。
- 单一 Dialog、tooltip、drag preview、表单 draft 等短期 UI 状态留在组件内。
- 后端是业务规则最终权威；前端预检只用于即时反馈，API 失败仍必须恢复 UI。
- 当前没有 `composables/` 层。不要为了单次使用新增抽象；只有逻辑确实被多个组件共享时再提取。
- 删除资料、别名、规则等破坏性操作必须先向使用者确认；确认文案要明确写出删除类型、所属对象与具体目标，取消后不得改变本地状态或发送写入请求。

## 3. 目录与模块职责

| 路径 | 当前职责 |
|---|---|
| `src/main.js` | 挂载 Vue、Pinia、Router 与全局 CSS |
| `src/router/index.js` | `/schedule`、`/completed-stats` 与根路径重定向 |
| `src/App.vue` | 应用壳层、顶栏、路由出口、主题、Email Dialog、设置 Dialog |
| `src/views/ScheduleView.vue` | 组合 XLSX 上传、手动新增、待排清单、日历；管理当前聚焦工单 |
| `src/views/CompletedStatsView.vue` | 拉取完工统计并按订单月份筛选 |
| `src/components/WorkOrderImportButton.vue` | 点击/拖拽选择 XLSX 与副档名预检 |
| `src/components/ManualWorkOrderDialog.vue` | 订单来源与七个 canonical 字段的手动新增表单及前端校验 |
| `src/components/PendingWorkOrderList.vue` | 待排笔数/新增入口、卡片、工时调整、删除、复制、外部拖拽 |
| `src/components/WorkOrderCalendar.vue` | FullCalendar 配置与全部排程交互编排 |
| `src/components/CompletedStatsTable.vue` | 完工统计表呈现与月份筛选 UI |
| `src/components/AppSettingsDialog.vue` | 收件者、SMTP 寄件者、基础设置、字段识别设置四个 tab |
| `src/components/ImportFieldSettingsPanel.vue` | XLSX 自定义字段别名与加急文字规则 |
| `src/components/ScheduleEmailDialog.vue` | 收件人 tags、报表类型、日期/月与发送流程 |
| `src/components/MonthPicker.vue` | 年/月双 select，可限制为实际存在月份 |
| `src/components/HelpTooltip.vue` | 可复用、支持 hover/focus/Escape 的说明 tooltip |
| `src/stores/workOrderStore.js` | 工单、片段、统计、导入结果、全局工单提示 |
| `src/stores/appSettingsStore.js` | 基础设置、SMTP 摘要、收件者与对应提示 |
| `src/api/http.js` | Axios instance 与统一错误正规化 |
| `src/api/workOrders.js` | 工单、片段、统计、Email API client |
| `src/api/settings.js` | 设置与收件者 API client |
| `src/assets/base.css` | 主题、布局、响应式、表单与 FullCalendar 样式 |

大型组件目前仍同时负责呈现和互动编排。修改时优先做局部、可验证的变更，不要顺手重写整个组件。

## 4. 路由与应用壳层

路由：

| URL | Route name | View |
|---|---|---|
| `/` | — | 重定向到 `/schedule` |
| `/schedule` | `schedule` | `ScheduleView` |
| `/completed-stats` | `completed-stats` | `CompletedStatsView` |

`App.vue` 负责：

- 顶部「待排工单」「完工统计表」导航。
- 全局「发送 Email」「设置」入口。
- 深色/浅色主题切换。
- 接收日历 `range-change`，作为 Email Dialog 的默认日期范围。
- 在内存中保存完工统计月份，跨路由返回时仍保留；它不会写入 localStorage。
- 用 query `settingsModal=1&tab=recipients|email|basic|fields` 保存设置 Dialog 与 tab。
- 默认设置 tab 为 `recipients`。

生产使用 HTML5 history。Spring Boot 的 SPA fallback 必须继续支持无扩展名的前端路由，且不得把缺失的 `/api/**` 资源回退到 `index.html`。

## 5. 状态管理

### 5.1 `useWorkOrderStore`

服务端状态：

- `pendingWorkOrders`
- `calendarEvents`
- `completedStats`
- `importResult`
- `activeRange`

UI 状态：

- `loading`
- `error`
- `notice`

刷新规则：

- XLSX 导入成功后并行刷新待排清单与当前可见日历；若日历尚未产生 `activeRange`，日历刷新不会发出请求。
- 建立排程后刷新待排与日历。
- 移动、resize、拆分、完成、暂停、继续后刷新日历。
- 删除片段后刷新待排与日历。
- 调整待排工时只替换对应工单并重新排序。
- `activeRange` 是日历最后一次查询区间；没有区间时 `refreshCalendarEvents()` 不发送请求。

待排清单由后端按下列完整顺序排序：

```text
latestShipTime ASC → urgent DESC → createdAt ASC
```

`WorkOrderResponse` 当前不含 `createdAt`，所以前端比较器实际上只会按期限与加急再次排序；JavaScript 的稳定排序会保留后端同值项目原有的 `createdAt` 次序。这里的「不支持自定义排序」是指不提供使用者手动排序。

API 日期时间以浏览器本机时间格式化为无 offset 的 `yyyy-MM-ddTHH:mm:ss`。修改日期处理前必须同时核对后端 `LocalDateTime` 语义。

### 5.2 `useAppSettingsStore`

管理：

- `settings`：基础设置与 `emailSender` 摘要。
- `importFieldSettings`：七个 canonical 字段的系统/自定义别名与加急文字规则。
- `emailRecipients`。
- `settingsLoaded`。
- settings/recipient 的 loading、saving、error。

收件者新增或更新后按下列顺序排序：

```text
lastUsedAt DESC → usageCount DESC → 姓名或 Email（zh-CN）
```

首次 GET 列表沿用后端顺序。

### 5.3 提示生命周期

- work-order store 的全局成功、错误与状态提示：5 秒。
- 复制订单编号成功：3 秒。
- 同类提示重复触发时重置计时器。
- 删除结果与检核/操作错误提示统一使用红色；其余一般成功或状态提示使用主题蓝色。
- 字段验证与 XLSX 逐行错误不是临时提示，不可自动消失。
- 组件不得直接绕过 store/helper 修改全局提示状态。

## 6. 本地持久化与 URL 状态

| Key / query | 内容 |
|---|---|
| `qn-calendar-theme` | `dark` / `light`，默认 `dark` |
| `qn-calendar-view` | `timeGridWeek` / `dayGridMonth` |
| `qn-calendar-date` | 日历当前基准日 `yyyy-MM-dd` |
| `qn-calendar-allow-past-scheduling` | 测试用「允许过去」开关 |
| `settingsModal=1` | 打开全局设置 |
| `tab=recipients|email|basic|fields` | 设置当前 tab |

周视图是连续 7 天；首次无保存日期时从今天开始。恢复 `qn-calendar-date` 后，不保证第一栏仍是今天。

## 7. API 对应

### 7.1 工单与片段

| Client 行为 | API | 使用状态 |
|---|---|---|
| 导入 XLSX | `POST /api/work-orders/import` | 使用中 |
| 手动新增待排工单 | `POST /api/work-orders` | 使用中 |
| 读取待排 | `GET /api/work-orders/pending` | 使用中 |
| 删除待排 | `DELETE /api/work-orders/{id}` | 使用中 |
| 读取日历 | `GET /api/work-orders/calendar?dateFrom&dateTo` | 使用中 |
| 读取完工统计 | `GET /api/work-orders/statistics/completed` | 使用中 |
| 待排拖入日历 | `PATCH /api/work-orders/{id}/schedule` | 使用中 |
| 移动/resize 片段 | `PATCH /api/work-orders/segments/{segmentId}` | 使用中 |
| 拖出日历 | `DELETE /api/work-orders/segments/{segmentId}` | 使用中 |
| 拆分片段 | `POST /api/work-orders/segments/{segmentId}/split` | 使用中 |
| 调整待排工时 | `PATCH /api/work-orders/{id}/duration` | 使用中 |
| 完成片段 | `PATCH /api/work-orders/segments/{segmentId}/done` | 现行完成 UI |
| 暂停 | `PATCH /api/work-orders/segments/{segmentId}/pause` | 使用中 |
| 继续 | `PATCH /api/work-orders/segments/{segmentId}/resume` | 使用中 |
| 发送 PDF Email | `POST /api/work-orders/schedule-email` | 使用中 |
| 直接完成整单 | `PATCH /api/work-orders/{id}/done` | client/store 保留，UI 未使用 |
| reopen | `PATCH /api/work-orders/{id}/reopen` | client/store 保留，UI 未使用；后端拒绝 DONE |

后端另有 `PATCH /api/work-orders/{id}/unschedule`，当前没有前端 client。

### 7.2 设置与收件者

| 行为 | API |
|---|---|
| 读取/保存基础设置 | `GET /api/settings`、`PUT /api/settings` |
| 读取来源删除影响/删除来源 | `GET /api/settings/order-sources/{identifier}/deletion-impact`、`DELETE /api/settings/order-sources/{identifier}` |
| 保存 SMTP 寄件者 | `PUT /api/settings/email-sender` |
| 读取/保存 XLSX 字段设置 | `GET /api/settings/import-fields`、`PUT /api/settings/import-fields` |
| 读取/新增收件者 | `GET /api/email-recipients`、`POST /api/email-recipients` |
| 编辑/删除收件者 | `PUT /api/email-recipients/{id}`、`DELETE /api/email-recipients/{id}` |

`http.js` 必须继续把失败正规化为：

```js
{
  message,
  status,
  details
}
```

## 8. XLSX 与待排清单

- 上传同时支持点击选档和 drag/drop。
- 前端只验证文件名以 `.xlsx` 结尾；后端由 POI 尝试解析工作簿，并验证表头与每个资料行内容。
- 上传说明明确提示：文件名包含基础设置中的来源名称或单字标签时，以文件名辨识的来源为准。
- 导入结果显示新增数、更新数、小红书非待配货跳过数与持续显示的逐行错误。
- `store.loading` 会涵盖导入过程，但目前上传按钮不会据此 disabled 或显示 loading。
- 待排卡片作为 FullCalendar external event，duration 取 `actualMinutes`，没有时才回退 `estimatedMinutes`。
- 工时以 15 分钟增减，最低 15 分钟。
- 删除只允许由卡片按钮触发，删除前确认，并提供 loading、disabled、accessible name。
- 清单标题左侧显示笔数，右侧「新增待排工单」按钮打开表单；订单来源、订单编号、金额、期限必填且显示红色 `*`，建立后刷新待排清单。
- 手动新增的订单来源选项读取自基础设置；待排与日历使用后端回传的来源单字标签，待排标签颜色也使用来源设置值。
- pointer 位移小于 6px 的短按会复制不含 `#` 的订单编号；拖拽不可触发复制。
- 待排卡片 focus/hover 显示价格、工时、状态、最晚发货、备注 tooltip。
- 聚焦待排工单时，周视图显示最晚发货红线。

待排卡固定三列信息：

1. `#订单编号`；右上角来源标记为蓝色「千」或红色「书」。
2. `¥价格` 与工时控制。
3. 红色时钟最晚发货时间与删除按钮。

不显示「待排」状态标签，不重复显示「订单价格」文字。加急不显示「急」字，仍以红框及 tooltip `[加急]` 表示。

## 9. FullCalendar 交互约束

### 9.1 基本设置

- 支持 `timeGridWeek` 与 `dayGridMonth`。
- 周视图隐藏 all-day slot，显示连续 7 天。
- 「允许过去」关闭时，周视图按上一页若会早于今天，会直接跳回今天而不是进入更早区间。
- 可视格为 30 分钟，drag/resize snap 为 15 分钟。
- 月视图只选择日期，正式排程时间固定到该日 `09:00:00`。
- 只有周视图允许 resize；月视图仍允许拖动。
- 周视图加载后滚到当前区间最早工单；没有工单时使用全局「周表默认开始时间」。
- 日期显示 `yyyy-MM-dd`，日期时间显示 `yyyy-MM-dd HH:mm:ss`，24 小时制。
- 周表头只显示「日号 + 星期」，月表头只显示星期。

### 9.2 手动排程验证

建立、移动、resize 前端都必须：

1. 将开始与结束向上正规化到 15 分钟边界。
2. 默认拒绝排到浏览器今天以前；「允许过去」只用于测试历史数据。
3. 拒绝结束晚于 `latestShipTime`。
4. 检查与不同 `workOrderId` 的可见事件重叠，DONE 也参与。
5. 允许同工单相邻/重叠，交由后端融合。

与不同工单重叠时：

- 原落点若已经违反今天下限或最晚发货时间，直接拒绝，不进入贴齐搜索。
- 分别寻找冲突链前方和后方最近的可用相邻区间。
- 过滤违反今天下限或最晚发货时间的候选。
- 选择与原落点位移较小者。
- 没有候选才拒绝并 revert。

后端仍会对全部数据做最终重叠与期限验证，不能把前端可见区间检查当成唯一保护。

### 9.3 API 失败恢复

- external drop：调用 `revert`、移除临时事件、显示错误。
- event drop / resize：调用 `info.revert()`。
- 拖出日历的 optimistic remove 失败：重新查询可见日历。
- 拖拽预览显示候选开始、结束、工时与拒绝原因，并保持在最上层。

### 9.4 片段、完成与计时

- 事件卡以 `[千]` / `[书]` 前缀显示订单编号，再显示 `HH:mm~HH:mm`、整单总排程工时、最晚发货日期与秒级时间。
- hover/focus tooltip 额外显示暂停时长、状态、价格与备注。
- tooltip 使用实际渲染尺寸在鼠标右下定位，空间不足时向左/上翻转并限制于 viewport；指针或焦点进入完成、拆分、暂停/继续操作区时必须隐藏，不能视觉遮挡按钮。
- 聚焦任一片段时，同工单全部可见片段加选中外框；周视图显示期限红线。
- 所有非 DONE 卡都会显示拆分按钮；片段不足 30 分钟时点击会显示错误，达到 30 分钟才会按最近 15 分钟边界的中点拆分。
- DONE 卡不显示完成、拆分、暂停按钮；现行 UI 不提供取消完成。
- 未完成、片段日期等于浏览器今天且现在已到开始时间时，显示暂停/继续按钮。
- `scheduleStartLocked` 代表今日片段已有暂停历史：
  - 仍可等长整体移动。
  - 顶部 resize handle 隐藏。
  - resize 必须保持开始不变且只能延后结束。
- 完成、暂停、继续后的真实时间调整与自动顺延由后端决定；前端刷新日历接受服务端结果。

事件状态 class 可叠加，不要改成互斥：

- `work-order-selected`
- `work-order-done`
- `work-order-urgent`
- `work-order-overdue`
- `work-order-paused`
- `work-order-pause-history-resize`

### 9.5 拖出日历

- pointer 在日历范围外放开时立即 optimistic remove，不播放回弹。
- 原片段是北京业务「今天」时，前端先移除该工单全部可见片段；其他日期先移除指定片段。
- 无论哪种情况都调用 `DELETE /api/work-orders/segments/{segmentId}`。
- 整单回待排或只删除单片段的最终判断必须由后端完成。

## 10. 完工统计

- 页面一次读取全部 DONE 统计。
- 订单月份来自 `orderTime`，不是 `completedAt`。
- 月份筛选在前端完成；空值代表全部。
- 表格固定字段：订单编号、备注、价格、预估工时、实际工时、暂停时长、工时差、时薪。
- 工时差为 0 显示「符合预期」，正数「超出」，负数「提前」。
- 金额与工时数值靠右，时薪保留两位小数。
- 统计页使用 `h/m` 显示工时；待排和日历使用「小时/分钟」。除非需求明确变更，不要顺手统一。

## 11. 全局设置

四个 tab：

1. `recipients`：Email 收件者。
2. `email`：Email 寄件者。
3. `basic`：基础设置。
4. `fields`：XLSX 字段识别设置。

打开 Dialog 时并行读取基础设置、字段设置与收件者。切 tab、取消、提交成功或重新打开时，依各表单规则清除验证。

基础设置：

- 预估工时基础金额必填、大于 0、最多两位小数。
- 周表默认开始时间只接受 `HH:00` 或 `HH:30`。
- 基础金额与周表开始时间各自在 `change` 时通过 `PUT /api/settings` 自动保存；成功提示显示在对应字段标题右侧并于 5 秒后消失，不提供基础设置总保存按钮。
- 订单来源选项使用横跨两栏的 InputTag，默认千牛、小红书；名称、identifier、标签色码与单字标签都必须显示红色 `*` 并通过既有校验。新增后自动聚焦 identifier；未填写 identifier 就重新聚焦添加输入框时，须确认是否放弃，确认后移除未保存标签，取消则重新聚焦 identifier。点击既有标签可编辑并使用编辑器内的保存按钮提交。支持 1–20 个名称/identifier 不重复的选项。
- 删除已保存来源前通过后端读取受影响工单数；有工单时确认文案必须明确说明工单与排程会永久删除。确认后调用来源删除 API，并刷新待排、日历与完工统计；红色删除提示显示在订单来源字段标题右侧 5 秒。
- 来源编辑保存成功后刷新待排清单、目前日历区间与完工统计，使来源名称、标签文字与颜色立即生效。
- 设置 Dialog 宽度为 860px 上限；标题、四个 tab 与关闭按钮同一横排，tab 不换行，宽度不足时允许水平滚动并只在滚动期间显示滚动条。

字段设置：

- 每个字段以一份 InputTag「别名」清单显示；「别名」位于标准字段名右侧，预设别名在前且不可删除，自定义别名接在后方并提供删除按钮；输入后按 Enter 或逗号新增，自动保存成功后清空并继续聚焦同一输入框；输入为空时按 Backspace 可从最后一个自定义别名开始删除，但仍须经过删除确认。
- 同一工作簿对同字段同时命中系统与自定义别名时，自定义别名优先；同类同时命中多个仍由后端拒绝。
- 备注标签字段可维护完全匹配/包含文字两种加急规则；`红旗` 只作为输入范例，不是默认规则。
- alias trim、转小写并移除空白、`_`、`-` 后不可跨字段重复，也不可与系统别名冲突。
- 订单付款时间不得显示或新增订单时间、下单时间、下单日期的繁简别名。
- 新增或确认删除别名、加急文字后立即以完整七字段快照自动保存；失败时回滚 draft 并显示错误，不提供额外保存按钮。
- 自动保存成功提示显示在对应「别名」文字右侧，内容为具体项目已添加或已删除，并在 5 秒后消失。
- 删除别名的确认文案必须指出字段与别名；删除加急文字也必须指出文字内容。
- 自定义字段名与加急文字最长 120 字；字段错误就近显示。

SMTP：

- 已设置时显示遮罩摘要。
- 编辑时回填 sender Email、host、port、security。
- `••••••••` 代表沿用已存授权码，送 API 时转为 `null`。
- sender Email、授权码、host 必填。
- 前端目前只提供 465、587；security 为 `NONE`、`SSL`、`STARTTLS`。
- 授权码输入默认遮罩，可切换可见。

收件者：

- 支持新增、行内编辑、确认删除。
- 手动管理时姓名与 Email 都必填；姓名最多 120 字，Email 最多 320 字。
- 只有内容变化时才启用新增/保存。
- 单一收件者的 API 编辑错误留在对应卡片。

## 12. Email Dialog

- 类型为 `WEEK`、`MONTH`、`COMPLETED_STATS`。
- 排程页默认跟随当前日历视图；完工统计路由默认 `COMPLETED_STATS`。
- 从月视图打开时默认类型是 `MONTH`；使用者切换到 `WEEK` 后，日期字段沿用当前焦点所在周的范围。
- 每次打开都刷新 SMTP 摘要、收件者和完工统计月份。
- 收件者使用 combobox + tags：
  - 姓名/Email 搜索。
  - 排除已选项。
  - 支持方向键、Enter、逗号、Backspace、Escape。
  - 可直接加入不在常用清单中的合法 Email。
  - Email 转小写并去重。
- 当前关闭再打开会保留同一组件实例中的已选收件者；不要在无需求时改变。
- 主题只读并自动生成，发送时重新计算。
- WEEK：起讫日期必填，结束不得早于开始。
- MONTH：月份必填，转换为完整月首/月末。
- COMPLETED_STATS：月份可空；空值发送 `dateFrom/dateTo = null`，代表全部。
- SMTP 未配置时禁止发送，并提供跳转寄件者设置入口。
- 成功后提示 5 秒，Dialog 不自动关闭。
- 发送或初始化失败会写入 `workOrderStore.error`；Dialog 本身没有错误区，现有全局错误显示在底层排程页或完工统计页，可能被遮罩挡住。

## 13. 表单、无障碍与通用 UI

- 系统可见中文使用简体中文，源码与 HTTP 内容使用 UTF-8。
- 自定义验证的 `<form>` 使用 `novalidate`，提交时一次验证当前画面的全部必填字段。
- 字段错误必须显示在对应 label 旁，并设置 `aria-invalid`、`aria-describedby`。
- invalid 控件使用 `--danger`；focus ring 仍使用 `--primary` / `--primary-soft`。
- 字段错误出现后，不因 input/change/blur/focus/计时自动消失；只在取消、切 tab、成功重置或下次重新打开等明确边界清除。
- 只有无法对应单一字段的 API/网络错误才放表单层或全局。
- `HelpTooltip` 是字段说明的默认实现；问号紧邻标题，hover/focus 显示，Escape 可关闭。
- 可拖动对象使用 `grab/grabbing`；resize 使用 `ns-resize`。
- 按钮提供清楚文字或 accessible name；loading 时同步 disabled。

视觉与布局：

- 第一屏直接显示可操作排程，不做 landing page 或 marketing hero。
- 主色为蓝色，不用绿色作为主操作色；不使用装饰性渐层球、bokeh、无意义插画。
- 不把卡片包在卡片内。
- 全局使用 `box-sizing: border-box`，包含 pseudo-elements。
- 主应用限制在 `100dvh`，超出内容在待排、日历、表格或 Dialog 内部滚动。
- 顶栏主要操作保持同一列；窄视口不得因换行把动作拆散。
- 980px 以下排程区改为上下堆叠；有足够宽度时待排卡可多栏，每卡约需 420px。
- 620px 以下双栏表单改为单栏。
- 设置 Dialog 的 tablist 在宽度足够时完整显示；宽度不足时使用单行水平滚动，不可换行或截断按钮文字。
- 文本不得溢出按钮、卡片、侧栏、表格或日历事件容器。

## 14. 验证与当前边界

前端目前没有 unit、component 或 E2E 测试，也没有 `test`、`lint` script。修改后最低验证：

```bash
cd frontend
npm run build
```

需要运行中系统时，不要在主机上单独启动 Vite 或 Spring Boot；依根目录规则使用 `docker-compose.dev.yml` 启动完整开发环境。正式静态资源与单一 JAR 回归才使用 production Compose。

后端 `SpaResourceConfigurationTests` 只验证生产静态资源 fallback/cache，不覆盖 Vue 互动。高风险日历或表单变更需明确列出人工验证范围；不要把 production build 成功描述成行为测试通过。

当前边界：

- `index.html` 仍声明 `lang="zh-Hant"`，与现行简体中文可见文案规则不一致。
- `WorkOrderCalendar.vue`、`AppSettingsDialog.vue`、`ScheduleEmailDialog.vue` 逻辑较大，但尚未建立 composable 层。
- `markAsDone(id)`、`reopen(id)` 是未被现行 UI 使用的保留 client/store action。
- 不提供使用者自定义排序。
- 不提供自动初始排程算法；「重叠时贴齐」只处理当前拖放意图。
- 不实现复杂权限系统，除非另有明确需求。
- Email/PDF 发送可能超过 Axios 20 秒 timeout；变更 timeout 或重试前必须先处理重复寄送语义。
- 当前没有 CORS 配置；不要假定将 `VITE_API_BASE_URL` 指向任意跨域服务即可使用。
