# hand-off-doc.md

## 未完成事項

### 完工統計表

#### 假設與不確定性

- 「工單完成之後」先解讀為 `status = DONE` 的工單才納入統計。
- 「實際使用的總時長」先解讀為同一工單所有完成後日曆 segment 的分鐘數加總。
- 「最初預估的時間」使用 `estimatedMinutes`，也就是系統依訂單金額計算出的原始預估工時；即使使用者後續調整 `actualMinutes`，統計仍保留 `estimatedMinutes` 作為對照基準。
- 「訂單備注」目前資料模型尚未看到對應欄位；實作前需要確認 XLSX 是否已有備注欄位。若沒有，要新增 `note` / `remark` 欄位、匯入解析與前端顯示。
- 「這一單的時薪」先解讀為 `訂單價格 / 實際總工時小時數`。若實際總工時為 0，顯示 `-` 或不計算，避免除以 0。

#### 可驗證完成條件

- 完成工單統計頁只列出已完成工單，不列出進行中 / 未完成工單。
- 每列顯示：訂單編號、訂單備注、訂單價格、實際總時長、原本預估時長、差異時間、時薪。
- 差異時間可清楚區分「超出預期」與「提前完成」：
  - `實際總時長 - 預估時長 > 0`：超出預期。
  - `實際總時長 - 預估時長 < 0`：提前完成。
  - `= 0`：符合預期。
- 實際總時長等於同一工單所有 segment 的加總，segment 之間的空白不計入。
- 時薪依實際總時長計算，格式清楚，例如 `$120 / 小時`。
- 統計頁刷新後資料仍正確，且和日曆 segment 資料一致。

#### 實作計劃

1. 資料模型確認與補欄位
   - 確認 XLSX 是否有訂單備注欄位。
   - 若有：在匯入服務中解析備注欄位。
   - 若沒有但需要手動維護：新增 `work_order.remark` 欄位與 DTO。
   - 更新 `WorkOrder`、`WorkOrderResponse` 或新增統計專用 DTO。

2. 後端統計 API
   - 新增完成工單統計 DTO，例如 `CompletedWorkOrderStatsResponse`。
   - 新增 repository/service 查詢 `DONE` 工單與其 segments。
   - 計算：
     - `estimatedMinutes`
     - `actualTotalMinutes = sum(segment minutes)`
     - `deltaMinutes = actualTotalMinutes - estimatedMinutes`
     - `hourlyRate = price / (actualTotalMinutes / 60)`
   - 新增 endpoint，例如：
     - `GET /api/work-orders/statistics/completed`
   - 排序建議先用 `completed_at DESC, latest_ship_time ASC`，若使用者要別的排序再調整。

3. 前端統計頁
   - 新增 API client 與 Pinia action 讀取完成工單統計。
   - 新增統計頁 / view，呈現表格。
   - 表格欄位：
     - 訂單編號
     - 訂單備注
     - 訂單價格
     - 實際總時長
     - 原本預估時長
     - 超出 / 提前時間
     - 時薪
   - 加入簡單導覽入口，避免影響目前排程主畫面。

4. 顯示與格式
   - 分鐘數統一顯示為 `x小時x分鐘`。
   - 差異時間使用語意標籤，例如「超出 35分鐘」、「提前 1小時10分鐘」、「符合預期」。
   - 時薪保留整數或最多 2 位小數，需確認最終格式。

5. 測試與驗證
   - 後端測試：
     - 多 segment 加總。
     - 提前完成、超出預期、符合預期。
     - 實際總時長為 0 時時薪不除以 0。
     - 備注欄位匯入 / 回傳。
   - 前端驗證：
     - `npm run build`。
     - 瀏覽器確認統計頁欄位、排序、格式與日曆 segment 加總一致。
