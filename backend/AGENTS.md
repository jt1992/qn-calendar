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
- 本地開發預設啟用 demo 工單資料，啟動時補齊缺少的 demo 訂單；可用 `APP_DEMO_DATA_ENABLED=false` 關閉。

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
5. 價格每 100 元轉換為 1 小時工時，後端一律以分鐘儲存。
6. `最晚發貨日期` 若只有日期沒有時間，後端視為當天 `23:59:59`。
7. 日期時間格式一律優先使用 `yyyy-MM-dd HH:mm:ss`；純日期使用 `yyyy-MM-dd`。
8. 後端查詢 `order_no` 是否已存在。
9. 若已存在，跳過，不重新分析。
10. 若不存在，建立新的 `PENDING` 工單。
11. 回傳新增筆數、跳過筆數、錯誤列資訊。

去重規則：

- `order_no` 必須在資料庫有唯一約束。
- 即使前端重複送出，也不得產生重複工單。
- 已存在於待排、已排程、已完成狀態的訂單，下次 XLSX 匯入時都要跳過。

工時計算：

```java
int estimatedMinutes = price
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
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

移出日曆回待排：

```http
PATCH /api/work-orders/{id}/unschedule
```

規則：

- 只允許已排入日曆的工單使用。
- 清空 `scheduled_start`、`scheduled_end` 與 `completed_at`。
- 更新 `status = PENDING`。
- 保留 `actual_minutes`，讓工單回待排後仍使用目前工時長度。

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
  "skippedCount": 3,
  "errors": [
    {
      "row": 8,
      "message": "訂單編號不可為空"
    }
  ]
}
```

### 查詢待排工單

```http
GET /api/work-orders/pending
```

### 查詢日曆工單

```http
GET /api/work-orders/calendar?dateFrom=2026-06-08&dateTo=2026-06-14
```

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
- 重複訂單編號會跳過。
- 價格可正確換算工時。
- 排程結束時間超過最晚發貨時間會失敗。
- 排程時間不是 5 分鐘倍數會失敗。
- 完成工單會更新為 `DONE`。
- 取消完成會回到 `SCHEDULED`。
- Email 可產生 HTML 週曆內容。
