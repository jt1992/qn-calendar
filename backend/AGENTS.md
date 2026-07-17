# backend/AGENTS.md

本文件定義後端專屬規則。根目錄協作與 GitHub Flow 請看 [`../AGENTS.md`](../AGENTS.md)。

## 技術棧

- Java 21 優先，Java 17 可接受。
- Spring Boot 3.5.14。
- Spring Data JPA。
- Apache POI，用於 XLSX 解析。
- JavaMailSender，用於寄送 Email。
- Thymeleaf，用於產生 HTML Email。
- 使用一般關聯式資料庫即可。
- MVP 階段以 `work_order` 一張核心資料表完成主要功能。
- 本地開發不自動建立 demo 工單資料；資料從 XLSX 匯入或既有 SQLite 資料庫取得。
- 所有涉及「今天」與目前時間的工單規則預設使用北京時區 `Asia/Shanghai`，由 application `Clock` 統一提供，部署時可用 `APP_TIME_ZONE` 覆蓋。Docker/JVM 的 persistence runtime 必須維持 `UTC`，不可跟隨業務時區，避免既有 SQLite epoch 資料讀取時整體偏移。

## 核心領域模型

### WorkOrder

| 欄位 | 說明 |
|---|---|
| `id` | 工單 ID |
| `order_no` | 訂單編號，必須唯一 |
| `price` | 訂單價格 |
| `estimated_minutes` | 系統分析出的預估工時，單位為分鐘 |
| `actual_minutes` | 使用者實際調整後工時，單位為分鐘 |
| `urgent` | 是否加急 |
| `latest_ship_time` | 最晚發貨時間 |
| `status` | `PENDING` / `SCHEDULED` / `DONE` |
| `scheduled_start` | 排程開始時間 |
| `scheduled_end` | 排程結束時間 |
| `completed_at` | 完成時間 |
| `created_at` | 建立時間 |
| `updated_at` | 更新時間 |

不要加入 `sort_order`，目前不需要自訂排序。

```java
public enum WorkOrderStatus {
    PENDING,
    SCHEDULED,
    DONE
}
```

## XLSX 匯入規則

1. 前端以 `multipart/form-data` 上傳 XLSX。
2. 後端使用 Apache POI 解析 XLSX。
3. XLSX 至少必須包含三個欄位：`訂單編號`、`訂單價格`、`最晚發貨日期`。
4. 每一筆資料必須取得訂單編號 `orderNo`。
5. 價格每「預估工時基礎金額」轉換為 1 小時工時，預設基礎金額為 100，可由全局設定調整；後端一律以分鐘儲存。
6. `最晚發貨日期` 若只有日期沒有時間，後端視為當天 `23:59:59`。
7. 最晚發貨日期依 `商家备注`、`买家留言`、`应发货时间` 的順序取得；商家備註與買家留言使用相同的日期解析規則。
8. 日期時間格式一律優先使用 `yyyy-MM-dd HH:mm:ss`；純日期使用 `yyyy-MM-dd`。
9. 每一列必須先完整解析成功，再依 `order_no` 查詢既有工單；解析失敗不可留下部分更新。
10. 若已存在，更新價格、備註、加急、最晚發貨時間、訂單時間與重新計算的預估工時，但保留工單 ID、訂單編號、狀態、排程片段、暫停紀錄、完成時間與排程時間。
11. 已排程或已完成工單必須保留 `actual_minutes`；待排工單只有在尚未人工調整工時時，才讓 `actual_minutes` 跟隨新的 `estimated_minutes`。
12. 若不存在，建立新的 `PENDING` 工單。
13. 同一份 XLSX 內出現相同訂單編號時，每列都要解析，最後一筆有效資料生效；新增與更新筆數依唯一訂單編號計算。
14. 回傳新增筆數、更新筆數與錯誤列資訊，不使用「跳過筆數」。

去重規則：

- `order_no` 必須在資料庫有唯一約束。
- 即使前端重複送出，也不得產生重複工單。
- 已存在於待排、已排程或已完成狀態的訂單，下次 XLSX 匯入時都要更新匯入內容並保留原流程狀態。

工時計算：

```java
int estimatedMinutes = price
        .divide(estimatedHourlyBaseAmount, 0, RoundingMode.CEILING)
        .multiply(BigDecimal.valueOf(60))
        .intValue();
```

建立工單時：

```text
actual_minutes = estimated_minutes
```

## 查詢規則

待排工單：

```sql
ORDER BY latest_ship_time ASC, urgent DESC, created_at ASC
```

最晚發貨時間越近越前；若時間相同，才讓加急工單排前面，再以建立時間穩定排序。

日曆工單：

- 只回傳 `status IN (SCHEDULED, DONE)`。
- 完成工單不從日曆移除。

## 排程規則

任何排程操作都必須符合：

```text
scheduled_end <= latest_ship_time
```

適用操作：

1. 待排工單拖到日曆。
2. 日曆內拖動工單。
3. resize 調整工單長度。
4. 直接透過 API 更新排程時間。

後端必須驗證：

```java
if (request.scheduledEnd().isAfter(workOrder.getLatestShipTime())) {
    throw new IllegalArgumentException("排程結束時間不可超過最晚發貨時間");
}
```

工時長度規則：

- 工時以分鐘儲存。
- 所有排程時間必須符合 5 分鐘粒度。
- 排程結束時間必須晚於開始時間。
- 工時必須是 5 分鐘倍數。

```java
long minutes = Duration.between(
        request.scheduledStart(),
        request.scheduledEnd()
).toMinutes();

if (minutes <= 0) {
    throw new IllegalArgumentException("排程結束時間必須晚於開始時間");
}

if (minutes % 5 != 0) {
    throw new IllegalArgumentException("工時必須是 5 分鐘的倍數");
}
```

排程更新時：

```text
actual_minutes = scheduled_end - scheduled_start
status = SCHEDULED
```

今天、尚未完成且已有暫停紀錄的片段：

- 日曆內移動必須保留原工時長度，允許開始與結束時間一起平移。
- 移動後，只有 `paused_at` 與非空的 `resumed_at` 都仍位於新片段區間內的暫停紀錄可以保留；超出區間的整筆暫停紀錄必須刪除。
- 若移動後與同工單其他片段融合，仍有效的暫停紀錄必須轉移到保留片段，不可因融合而遺失。
- resize 必須固定開始時間，只允許把結束時間延後；不可縮短結束時間，也不可從上邊緣改變開始時間。
- 前述移動與 resize 仍必須符合時間粒度、最晚發貨時間與不同工單不可重疊規則。

待排工單先行調整工時：

```http
PATCH /api/work-orders/{id}/duration
```

Request：

```json
{
  "actualMinutes": 95
}
```

規則：

- 只允許 `PENDING` 工單使用。
- `actualMinutes` 必須大於 0。
- `actualMinutes` 必須是 5 分鐘倍數。
- 此 API 不修改 `estimated_minutes`。

片段移出日曆：

```http
DELETE /api/work-orders/segments/{segmentId}
```

規則：

- 後端必須依被移出片段的原 `scheduled_start` 日期與北京業務日期判斷，不可只由前端分流。
- 今日片段：先刪除該工單全部暫停紀錄，再刪除全部排程片段；清空排程與完成時間、更新 `status = PENDING`，并重設 `actual_minutes = estimated_minutes`。
- 非今日片段：只移除指定片段；若仍有其他片段則保持已排，最後一段移除後才回待排。

## 完成工單規則

完成：

```http
PATCH /api/work-orders/{id}/done
```

日曆片段完成：

```http
PATCH /api/work-orders/segments/{segmentId}/done
```

後端更新：

```text
status = DONE
completed_at = now
```

日曆片段完成時，若目前時間已超過該片段 `scheduled_end`，後端要先把該片段結束時間延長到目前時間並向上取 5 分鐘，再標記工單完成；仍需符合最晚發貨時間與不同訂單編號不可重疊規則。

取消完成：

```http
PATCH /api/work-orders/{id}/reopen
```

後端更新：

```text
status = SCHEDULED
completed_at = null
```

## 重疊規則

- 不同訂單編號的工單不可重疊。
- 完成工單仍保留在日曆中，也不可與其他訂單編號的工單重疊。
- 同一訂單編號的分割片段若時間相鄰或重疊，後端必須自動融合成同一片段。
- 後端必須阻擋不同訂單編號的排程片段時間重疊。

## API 規格

### 匯入 XLSX

```http
POST /api/work-orders/import
Content-Type: multipart/form-data
```

Response：

```json
{
  "createdCount": 10,
  "updatedCount": 3,
  "errors": [
    {
      "row": 8,
      "message": "訂單編號不可為空"
    }
  ]
}
```

### 刪除待排工單

```http
DELETE /api/work-orders/{id}
```

只允許刪除 `PENDING` 工單，成功回傳 `204 No Content`；已排程或已完成工單必須拒絕刪除。

### 查詢待排工單

```http
GET /api/work-orders/pending
```

### 查詢日曆工單

```http
GET /api/work-orders/calendar?dateFrom=2026-06-08&dateTo=2026-06-14
```

### 全局設定

```http
GET /api/settings
PUT /api/settings
```

```json
{
  "estimatedHourlyBaseAmount": 100,
  "weekViewDefaultStartTime": "06:00"
}
```

`weekViewDefaultStartTime` 使用 `HH:mm`，以 30 分鐘為單位，預設為 `06:00`。

### 更新排程

```http
PATCH /api/work-orders/{id}/schedule
```

```json
{
  "scheduledStart": "2026-06-08T09:00:00",
  "scheduledEnd": "2026-06-08T11:00:00"
}
```

### 發送排程 Email

```http
POST /api/work-orders/schedule-email
```

```json
{
  "to": ["someone@example.com"],
  "subject": "工單排程表",
  "dateFrom": "2026-06-08",
  "dateTo": "2026-06-14",
  "viewType": "WEEK"
}
```

MVP 先只支援 `viewType = WEEK`。

## Email 功能規格

Email 內容必須是靜態 HTML Table 週曆，不可嵌入 Vue、FullCalendar JavaScript，且不可只寄前端截圖。

Email 應呈現：

- 左側時間列。
- 上方星期 / 日期欄。
- 工單以卡片方式顯示在對應日期與時間格。
- 加急工單顯示加急標籤與紅色提示。
- 完成工單淡化顯示。
- 不同訂單編號的工單不應出現在同一時間格；若同一訂單編號有多個片段，依片段時間呈現。
- 至少包含訂單編號、時間、加急狀態、完成狀態、最晚發貨時間。

Email 交易順序：

- 先以短唯讀交易取得郵件資料，再於交易外產生附件並執行 SMTP 寄送。
- 只有 SMTP 確認寄送成功後，才以獨立短寫入交易新增或更新 Email 收件者。
- 不可用同一個長交易包住資料查詢、PDF 產生、SMTP 寄送與收件者寫入，避免 SQLite 讀寫鎖衝突。

## 後端模組切分

- `WorkOrderImportService`：XLSX 解析、訂單去重、工時計算、建立待排工單。
- `WorkOrderService`：查詢待排工單、查詢日曆工單、標記完成、取消完成。
- `WorkOrderScheduleService`：排程更新、最晚發貨時間驗證、5 分鐘粒度驗證。
- `WorkOrderEmailService`：查詢指定區間排程、建立 Email 週曆 view model、使用 Thymeleaf 產生 HTML、寄信。

不要把所有邏輯塞在單一 Service。

## 驗證與錯誤處理

後端必須驗證：

- XLSX 是否為有效格式。
- 訂單編號不可為空。
- 價格不可為負數。
- 最晚發貨時間不可為空。
- 排程結束時間必須晚於開始時間。
- 排程時間必須符合 5 分鐘粒度。
- 排程結束時間不可超過最晚發貨時間。
- Email 收件者不可為空。
- 人工新增或修改 Email 收件者時，收件人姓名必填；SMTP 成功寄送後自動建立的收件者可暫時沒有姓名。
- Email 日期區間不可無效。

使用 `@ControllerAdvice` 集中轉換例外為可顯示的 API 錯誤，不把 stack trace 回傳給前端。

## 不要實作

- 不要做自訂工單排序。
- 不要加入 `sort_order`。
- 不要做自動排程演算法。
- 不要允許不同訂單編號的工單重疊。
- 不要把 Email 做成純文字清單。
- 不要在 Email 中放 JavaScript。
- 不要一開始就拆太多資料表。
- 不要做複雜權限系統，除非另有要求。

## 建議測試項目

- 匯入新 XLSX 會建立新工單。
- 重複訂單編號會更新匯入內容並保留原流程狀態。
- 同一份 XLSX 的重複訂單編號以最後一筆有效資料為準。
- 日曆內移動後會清除超出新片段區間的暫停紀錄，仍有效紀錄會保留。
- 今日已有暫停紀錄的片段只能把結束時間 resize 延後，不可縮短或改變開始時間。
- 今日片段移出日曆後會清空整張工單的片段與暫停紀錄並回待排。
- 待排工單可刪除，已排程與已完成工單不可透過待排刪除 API 移除。
- 價格可正確換算工時。
- 排程結束時間超過最晚發貨時間會失敗。
- 排程時間不是 5 分鐘倍數會失敗。
- 完成工單會更新為 `DONE`。
- 取消完成會回到 `SCHEDULED`。
- Email 可產生 HTML 週曆內容。
