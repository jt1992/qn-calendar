# hand-off-doc.md

## 未完成事項

### real_order.xlsx 匯入欄位、備註、15 分鐘排程調整計畫

#### 假設與不確定性

- 本輪先寫計畫，不先實作功能；`real_order.xlsx` 與可能產出的根目錄 Markdown 預覽檔仍需在實作時加入 `.gitignore`。
- `real_order.xlsx` 目前只有 `export` 工作表，共 36 筆資料；實際欄位包含需求指定的 `订单编号`、`买家实付金额`、`买家留言`、`备注标签`、`商家备注`、`应发货时间`，且發貨日期解析還需要用到 `订单付款时间` 作為月份 fallback。
- 後續匯入必須依欄名 canonical mapping 取值，不能依欄位順序。
- `商家备注` 的發貨日期優先規則已確認需要支援 `M.dd发！` / `MM.dd发！` 這類開頭，也要支援真實檔中類似 `29号发！！！`、`5月30号收到...`、`4.28/29发...` 的可辨識日期。商家備註日期若沒有年，預設為今年；若沒有年也沒有月，月份預設使用同列 `订单付款时间` 的月份；無法明確解析時才回退 `应发货时间`。
- `应发货时间` 目前是文字，例如 `子订单...： 2026-06-13 14:06前 ;`，同一訂單可能有多個子訂單時間；解析時應擷取所有 `yyyy-MM-dd HH:mm前`，取最早時間作為最晚發貨限制。
- 系統狀態維持既有 `PENDING` / `SCHEDULED` / `DONE`，前端文案對應為待排 / 未完成 / 完成，不新增狀態。

#### 可驗證完成條件

- `.gitignore` 忽略 `real_order.xlsx`，以及根目錄本地預覽 Markdown 檔（建議檔名 `real_order.preview.md`）。
- 可從 `real_order.xlsx` 產出好讀的根目錄 Markdown 預覽檔，且該檔不納入 git。
- XLSX 匯入以欄名讀取 `订单编号` 去重、`买家实付金额` 估算原始工時、`备注标签` 判斷加急、`买家留言` + `商家备注` 組合為訂單備註、`商家备注` / `应发货时间` 解析最晚發貨時間。
- `商家备注` 發貨日期缺年時用今年，缺年缺月時用同列 `订单付款时间` 的月份，不依欄位位置取值。
- 訂單備註格式符合：
  - 只有買家留言時：`买家留言：xxx`
  - 只有商家備註時：`商家备注：xxx`
  - 兩者都有時：兩行或同一欄內清楚列出 `买家留言：xxx` 與 `商家备注：xxx`
  - 都沒有時：`无任何备注`
- 完工統計表顯示上述訂單備註。
- 待排工單卡片與日曆工單 hover tooltip 顯示訂單備註。
- 前端排程粒度改為 15 分鐘：待排工時加減、拖曳、resize、拆分點與提示文字一致。
- 後端排程驗證、待排工時更新、完成時自動延長結束時間、拆分驗證都改為 15 分鐘粒度。
- Email 週曆以 15 分鐘 slot 輸出，並在工單卡片中顯示訂單備註。
- 既有不同訂單不可重疊、同訂單相鄰或重疊自動融合、結束時間不可超過最晚發貨時間規則仍通過測試。

#### 工作階段與 commit 拆分

1. `chore: ignore local order preview files`
   - 更新交接文件的階段拆分。
   - 更新 `.gitignore`，忽略 `real_order.xlsx` 與 `real_order.preview.md`。
   - 產出本地 `real_order.preview.md`，確認欄位與代表解析結果。

2. `feat: import real order xlsx fields`
   - 實作真實 XLSX 欄位 mapping、備註組合、`商家备注` / `应发货时间` / `订单付款时间` 發貨日期解析。
   - 補後端匯入測試。

3. `feat: use fifteen minute schedule granularity`
   - 後端排程、片段、完成延長、待排工時與 Email slot 改為 15 分鐘。
   - Email 內容加入訂單備註。
   - 補後端排程與 Email 測試。

4. `feat: show order remarks in schedule UI`
   - 前端待排、日曆、hover tooltip、拖曳資料、文案與 15 分鐘操作粒度同步。

5. `chore: update import and scheduling docs`
   - 更新 `README.md`。
   - 功能完成後移除本交接事項，讓 `hand-off-doc.md` 回到只記錄未完成事項。

#### 實作步驟

1. 本地檔案與預覽
   - 更新 `.gitignore`：加入 `real_order.xlsx` 與 `real_order.preview.md`。
   - 以 bundled Python/pandas 或專案可用工具讀取 `real_order.xlsx`，輸出根目錄 `real_order.preview.md`，內容只用於本地檢查欄位、代表列與解析結果，不提交。

2. 後端匯入欄位與備註
   - 修改 `backend/src/main/java/com/qn/calendar/workorder/service/WorkOrderImportService.java`。
   - `canonicalHeader` 新增：
     - `买家实付金额` -> `price`
     - `买家留言` -> `buyerMessage`
     - `商家备注` -> `merchantRemark`
     - `备注标签` -> `urgent`
     - `应发货时间` -> `latestShipTime`
     - `订单付款时间` -> `paidAt`
   - 建立匯入時的 `remark` 組合 helper，寫入既有 `WorkOrder.remark`。
   - 建立最晚發貨解析 helper：
     - 先解析 `商家备注` 開頭的發貨日期；日期有年就用原年，沒有年就用今年，沒有年也沒有月就用同列 `订单付款时间` 的月份；時間預設當天 `23:59:59`。
     - `订单付款时间` 也必須依欄名解析，不能依欄位位置；若缺年缺月又無法取得付款月份，該商家備註日期視為不明確並回退 `应发货时间`。
     - 無法解析商家備註時，從 `应发货时间` 擷取所有 `yyyy-MM-dd HH:mm前`，取最早時間。
     - 若欄位為標準日期 cell 或既有日期字串，保留現有 fallback。
   - 建立匯入測試，覆蓋欄位順序變動、重複 `订单编号` 跳過、備註組合、商家備註日期優先、缺年用今年、缺年缺月用 `订单付款时间` 月份、`应发货时间` 多子訂單解析。

3. 後端 15 分鐘粒度
   - 修改 `WorkOrderService`、`WorkOrderSegmentService`、`UpdateWorkOrderDurationRequest`、`WorkOrderEmailService` 中的 5 分鐘常數與文案為 15 分鐘。
   - 建議先抽出單一 `SCHEDULE_GRANULARITY_MINUTES = 15` 常數於 `WorkOrderTimeUtils` 或服務內最小共用位置，避免 15 分鐘規則分散。
   - 更新完成片段時的向上取整為 15 分鐘。
   - 更新後端測試：`WorkOrderServiceTests`、`WorkOrderSegmentServiceTests`、`WorkOrderEmailServiceTests`，把不合法案例改成非 15 分鐘倍數，Email rows / rowspan 依 15 分鐘重算。

4. 前端待排與日曆
   - 修改 `frontend/src/components/PendingWorkOrderList.vue`：
     - `normalizeMinutes`、預設最小值、加減按鈕與 title 改為 15 分鐘。
     - `toExternalEvent` 帶上 `remark`，讓日曆接到外部拖曳事件後仍有備註。
     - 待排卡片加 `title` 或現有 tooltip 方式顯示訂單備註。
   - 修改 `frontend/src/components/WorkOrderCalendar.vue`：
     - `snapDuration` 改 `00:15:00`。
     - 拆分點計算、deadline marker duration、提示文案改為 15 分鐘。
     - `eventContent` / `showEventTooltip` / `updateEventTooltipFromPointer` 加入備註顯示。
   - 檢查 `frontend/src/stores/workOrderStore.js` 已保留 `remark`，必要時補外部拖曳接收後刷新行為即可。

5. Email 備註顯示
   - 修改 `backend/src/main/resources/templates/email/schedule-week.html`，在每張 Email 工單卡片加入 `order.remark`。
   - 保持靜態 HTML table，不加入 JavaScript。

6. 文件
   - 完成功能後更新根目錄 `README.md`：匯入欄位、訂單備註規則、15 分鐘粒度、Email 備註顯示。
   - 功能完成後刪除本交接事項，避免 `hand-off-doc.md` 留下已完成內容。

#### 驗證指令

- 後端：`cd backend && ./mvnw test`
- 前端：`cd frontend && npm run build`
- 人工驗證：
  - 上傳 `real_order.xlsx`，確認新增 36 筆或依資料庫既有訂單跳過，且不因欄位順序變動失敗。
  - 檢查待排列表排序仍為最晚發貨時間優先。
  - hover 待排與日曆卡片可看到訂單備註。
  - 拖曳 / resize 只能落在 15 分鐘邊界，非 15 分鐘 API 請求被後端拒絕。
  - Email 預覽或寄出內容以 15 分鐘 slot 呈現，並顯示訂單備註。
