# frontend/AGENTS.md

本文件定義前端專屬規則。根目錄協作與 GitHub Flow 請看 [`../AGENTS.md`](../AGENTS.md)。

## 技術棧

- 語言：JavaScript，使用大部分現代瀏覽器與 Node 支援的最新版 ECMAScript。
- 框架：Vue 3.5.35。
- 打包工具：Vite 8.0.16。
- 狀態管理：Pinia。
- 日曆：FullCalendar。
- HTTP：Axios。
- 目前指定版本：Axios 1.17.0、Pinia 3.0.4。

## 前端職責

- 上傳 XLSX。
- XLSX 上傳必須同時支援點擊選檔與拖曳上傳。
- 顯示待排工單。
- 顯示週 / 月日曆。
- 讓待排工單可拖曳到日曆。
- 讓日曆工單可拖動與 resize。
- 使用 `eventAllow` 先擋下超過最晚發貨時間的操作。
- 後端 API 失敗時 revert FullCalendar 操作。
- 顯示加急與完成狀態。
- 提供 Email 發送 Dialog。

前端限制只負責使用體驗，不能取代後端驗證。

## 目錄規則

- `src/api/`：Axios instance 與 API client。元件不可直接呼叫 Axios。
- `src/stores/`：Pinia store，管理跨畫面狀態。
- `src/components/`：畫面元件與互動元件。
- `src/assets/`：全域樣式與靜態資源。

## UI 與設計規則

- 第一屏應是可操作的排程介面，不做 landing page。
- SaaS / 營運工具風格應安靜、實用、資訊密度適中。
- 使用明確的工作區：待排工單、日曆、Email Dialog。
- 按鈕優先使用 icon + 清楚文字；若專案已有 icon library，優先使用該 library。
- 日曆工具列按鈕要保持緊湊；窄視口下 Email、週/月、上一段/今天/下一段仍應維持在同一操作列。
- 日曆標題與右側 Email / 週月 / 導覽控制應維持同一個 header row；標題下方說明放在日期下面，窄視口可縮小間距與字級，不要把操作列換到下一列。
- 全域 box model 使用 `box-sizing: border-box`，並包含 pseudo-elements。
- 任何可拖曳物件預設游標使用 `grab`，按下拖曳時使用 `grabbing`。
- 不使用裝飾性漸層球、bokeh、無意義插畫或 marketing hero。
- 不把卡片包在卡片內；重複資料項目可使用單層 card。
- 文字不可超出按鈕、卡片、側欄或日曆事件容器。
- 主要介面必須被限制在視口內，不讓整頁內容超出 `100dvh`；超出的待排清單、日曆內容與工具列使用區域內滾動補足。
- 待排工單清單在堆疊版面且寬度足夠時可一行兩張工單卡；每張卡至少約 420px 可用寬度，不足時要回到單欄，避免資訊擠壓。
- 側欄標題列左側標題 / 說明與右側深淺色切換按鈕要維持左右對齊且不換行；窄視口不可把切換按鈕推到下一行。
- 主題主色使用藍色，不使用綠色作為主操作色；加急、完成、主要操作要有清楚但克制的視覺差異。
- 前端必須提供 CSS 變數驅動的深色 / 淺色模式；預設深色模式，淺色模式不能改變資訊架構。
- 日曆日期顯示使用 `yyyy-MM-dd`，日期時間顯示使用 `yyyy-MM-dd HH:mm:ss`。
- 時間顯示使用 24 小時制，範圍為 `00:00:00` 到 `23:59:59`。
- 週檢視左側時間軸只顯示到分鐘，例如 `16:30`，不要顯示秒數。
- 週檢視時間格以半小時作為可視區間，但拖曳與 resize 的 snap 粒度仍維持 5 分鐘。
- 週檢視表頭在上方標題已顯示年月日時，只顯示「日號 + 星期」，例如 `07 日`，不要重複顯示年月。
- 月檢視的星期表頭只顯示星期，不可把 FullCalendar 傳入的 1970 參考日期顯示成日期。

## 待排工單

- 待排工單不需要手動排序。
- 後端查詢固定排序：`latest_ship_time ASC, urgent DESC, created_at ASC`。
- 待排工單以最晚發貨時間越近越前；若時間相同，才讓加急工單排前面。
- 前端應明確顯示加急標籤，例如「加急」。
- 工時以分鐘儲存，前端可顯示為小時。
- 待排工單卡片可先調整工時長度；調整值要更新 `actualMinutes`，並作為拖曳到日曆時的預設 duration。
- 待排工單卡片資訊固定分三列：訂單編號 + 狀態 / 加急 / 完成標記、訂單價格 + 工時控制器、最晚發貨時間。
- 待排工單卡片四邊 padding 應一致且不可在窄視口被壓縮到貼邊；三列資訊都要保留穩定內距。
- 訂單價格與工時控制器要保持在同一資訊列；不要在同一卡片重複顯示價格。
- 待排工單最晚發貨以時鐘 icon 取代文字標籤，使用紅字顯示，不可截斷，時鐘 icon 要與文字垂直對齊。
- 待排與日曆上的工時長度顯示使用 `x小時x分鐘`，不要使用小數小時如 `4.08h`。
- 待排工單 click / focus 時，日曆週檢視需要顯示該工單最晚發貨時間紅線。

## FullCalendar 規則

必要能力：

- 週 / 月切換。
- 刷新頁面後要保留使用者上次選擇的週檢視或月檢視。
- 週檢視以今天作為第一欄，後續日期依序往後排 7 天。
- 週檢視與月檢視都不可把工單拖曳排到今天以前的日期。
- 待排工單可拖曳到日曆。
- 日曆工單可自由拖動。
- 日曆工單可 resize 調整工時長度。
- 週檢視與月檢視都支援拖曳；月檢視只負責拖到日期，預設排到該日 `09:00:00`。
- 只有週檢視允許 resize 與 5 分鐘粒度的精準時間調整。
- 日曆工單 click / focus 時，週檢視需要顯示該工單最晚發貨時間紅線。
- 最晚發貨時間紅線必須是直線，不可使用圓角或看起來像卡片邊框的樣式。
- 日曆工單事件卡片必須完整顯示最晚發貨時間；窄視口不可截斷。
- 日曆工單卡片不提供 `X` 關閉 / 移出按鈕。
- 日曆工單直接拖出日曆範圍後放開時，事件應立即從日曆消失並回到待排工單，不播放回彈到原位的動畫。
- 日曆工單不使用彈出詳細卡執行完成；完成 / 取消完成應放在日曆事件卡片上。
- 日曆片段點完成時，若目前時間已超過該片段結束時間，後端會先把該片段結束時間延長到目前時間並向上取 5 分鐘，再標記完成。
- 日曆工單卡片右上角操作按鈕可半透明常駐，hover / focus 時變清楚，但不可擠壓或遮擋主要資訊。
- 月檢視日曆工單事件要撐滿日期格可用寬度；完成 / 拆分按鈕固定靠右上角。
- 日曆工單卡片內容允許換行，但資訊列應貼齊上方連續排列，不可因事件高度較高而把各行分散到整張卡片。
- 日曆工單卡片資訊固定分行為：訂單編號、開始時間 `HH:mm~HH:mm`、工時長、`最晚發貨：`、`yyyy-MM-dd`、`HH:mm:ss`。
- 日曆工單拖曳、resize 時要即時顯示起始時間、結束時間與 `x小時x分鐘` 工時。
- 日曆工單拖曳、resize 的提示卡片要在最上層，不可被日曆格線、拖曳鏡像或其他面板遮住。
- 日曆工單拖曳中的原 event / mirror 應比靜止狀態更透明，讓落點與警示更容易辨識。
- 日曆工單拖曳游標使用 `grab` / `grabbing`，resize 游標使用上下調整語意的 `ns-resize`。
- 週檢視日曆工單上下邊緣的 resize hit area 要比 FullCalendar 預設值更大，約 14px，方便延長 / 縮短工時。
- 時間粒度為 5 分鐘。
- 不同訂單編號的工單不可重疊。
- 同一訂單編號的分割片段若時間相鄰或重疊，允許前端操作送出並交由後端自動融合。
- 拖曳或 resize 時若落點與不同訂單編號的工單重疊，前端應保留拖曳意圖並自動貼齊到最近的可用相鄰時間；找不到可用位置時才 revert。
- 完成工單半透明顯示。

建議設定：

```js
const calendarOptions = {
  initialView: 'timeGridWeek',
  headerToolbar: false,
  slotDuration: '00:30:00',
  slotLabelInterval: '01:00:00',
  snapDuration: '00:05:00',
  slotLabelFormat: {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  },
  editable: true,
  eventDurationEditable: currentView.value === 'timeGridWeek',
  droppable: true,
  eventOverlap: true,
  slotEventOverlap: false,
  eventResizableFromStart: currentView.value === 'timeGridWeek'
}
```

最晚發貨時間前端限制：

```js
eventAllow(dropInfo, draggedEvent) {
  const latestShipTime = draggedEvent.extendedProps.latestShipTime

  if (!latestShipTime) {
    return true
  }

  if (currentView.value === 'dayGridMonth' || dropInfo.allDay) {
    const end = addMinutes(dateAtWorkdayStart(dropInfo.start), draggedEvent.extendedProps.actualMinutes)
    return end <= new Date(latestShipTime)
  }

  return dropInfo.end <= new Date(latestShipTime)
}
```

完成與加急樣式：

```js
eventClassNames(info) {
  if (info.event.extendedProps.status === 'DONE') {
    return ['work-order-done']
  }

  if (info.event.extendedProps.urgent) {
    return ['work-order-urgent']
  }

  return []
}
```

```css
.work-order-done {
  opacity: 0.35;
}

.work-order-urgent {
  border-left: 4px solid red;
  font-weight: 600;
}
```

## 前端事件對應

| 行為 | FullCalendar 事件 | API |
|---|---|---|
| 待排工單拖到日曆 | `eventReceive` | `PATCH /api/work-orders/{id}/schedule` |
| 日曆內拖動 | `eventDrop` | `PATCH /api/work-orders/{id}/schedule` |
| 調整工時長度 | `eventResize` | `PATCH /api/work-orders/{id}/schedule` |
| 點完成 | 自訂按鈕或 `eventClick` | `PATCH /api/work-orders/{id}/done` |
| 取消完成 | 自訂按鈕 | `PATCH /api/work-orders/{id}/reopen` |
| 移出日曆 | 拖出日曆範圍後放開 | `DELETE /api/work-orders/segments/{segmentId}` |
| 發送 Email | Button click | `POST /api/work-orders/schedule-email` |

Email Dialog 開啟時，開始日期與結束日期預設為目前日曆焦點所在週的週起訖。

## Pinia Store

建議 store：`useWorkOrderStore`

- `pendingWorkOrders`
- `calendarEvents`
- `importXlsx(file)`
- `fetchPendingWorkOrders()`
- `fetchCalendarEvents(dateFrom, dateTo)`
- `scheduleWorkOrder(id, start, end)`
- `markAsDone(id)`
- `reopen(id)`
- `sendScheduleEmail(payload)`

## 錯誤處理

- 匯入錯誤列資訊必須顯示。
- 拖曳超過最晚發貨時間時應提示。
- 寄信成功 / 失敗需提示。
- 排程更新失敗時必須 `info.revert()`。
- API 錯誤解析應集中在 API layer 或 store，不要散在元件內。

## 不要實作

- 不要做自訂工單排序。
- 不要做自動排程演算法。
- 不要允許不同訂單編號的工單重疊。
- 不要把 Email 做成純文字清單。
- 不要在 Email 中放 JavaScript。
- 不要做複雜權限系統，除非另有要求。
