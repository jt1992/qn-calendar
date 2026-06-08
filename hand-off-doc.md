# hand-off-doc.md

## 2026-06-08

- 建立 `feature/codex-project-architecture` 分支。
- 拆分根目錄、前端、後端 `AGENTS.md` 規則。
- 建立 Vue 3.5 + Vite 8.0 前端骨架，包含匯入、待排工單、FullCalendar、Email Dialog、Pinia store 與 Axios API layer。
- 建立 Spring Boot 3 + Java 21 後端骨架，包含 `work_order` entity、匯入、排程、完成/取消完成、週曆 Email 與集中錯誤處理。
- 加入 Dockerfile、`docker-compose.yml` 與 `.gitignore`。

## 2026-06-08 補充

- XLSX 匯入支援必備中文欄位：`訂單編號`、`訂單價格`、`最晚發貨日期`。
- 純日期型最晚發貨日會轉為當天 `23:59:59`。
- 前端日曆與 Email 日期時間顯示統一為 `yyyy-MM-dd HH:mm:ss` 與 24 小時制。
- 前端改為 CSS 變數驅動的深色 / 淺色模式，預設深色模式。

## 2026-06-08 依賴升級

- 後端 Spring Boot 升級到 `3.5.14`。
- 前端 Vue 升級到 `3.5.35`，Axios 升級到 `1.17.0`，Pinia 升級到 `3.0.4`，Vite 固定到 `8.0.16`。
- 前端深色 / 淺色模式主題主色由綠色改為藍色。

## 2026-06-08 前端操作細節

- XLSX 上傳改為較小的拖放區，支援點擊選檔與拖曳上傳。
- 日曆工具列按鈕縮小，窄視口下 Email、週/月、上一段/今天/下一段維持同一行。
- Email Dialog 日期預設為目前日曆焦點週的週起訖。
- 修正月檢視星期表頭誤顯示 `1970-01-*` 的問題。

## 2026-06-08 Demo 工單與拖曳規則

- 後端啟動時若資料庫沒有工單，會建立數筆 demo 工單，包含加急與一般單。
- Demo 工單顯示訂單編號、訂單價格、最晚發貨時間，可用 `APP_DEMO_DATA_ENABLED=false` 關閉。
- 週檢視與月檢視都支援拖曳；月檢視只排到指定日期並預設 `09:00:00` 開始，週檢視才允許精準時間與 resize 調整。

## 2026-06-08 待排卡片工時調整

- 待排工單卡片改為訂單編號與訂單價格同列顯示，不再重複顯示價格。
- 卡片內提供小型工時控制器，可用 +/- 以 5 分鐘為單位調整，顯示為 `x小時x分鐘`。
- 新增 `PATCH /api/work-orders/{id}/duration`，只允許 `PENDING` 工單更新 `actualMinutes`，拖曳到日曆時會使用此工時長度。

## 2026-06-08 日曆互動 UX

- 待排工單或日曆事件取得 focus / click 時，週檢視會以紅線標示最晚發貨時間。
- 日曆事件卡片提供 `X`，呼叫 `PATCH /api/work-orders/{id}/unschedule` 後回到待排工單，保留目前 `actualMinutes`。
- 日曆事件卡片內提供完成 / 取消完成按鈕，不再使用彈出詳細卡。
- 日曆拖曳與 resize 期間會分行顯示起始時間、結束時間與 `x小時x分鐘` 工時，超過最晚發貨時間時顯示警示。
- 日曆與待排可拖曳物件游標改為 `grab` / `grabbing`，resize 使用 `ns-resize`。
- 日曆事件資訊會在卡片內合理換行，最晚發貨時間不溢出卡片。
- 日曆事件卡片內容固定貼齊上方連續排列，不會因事件高度較高而把標題、工時、最晚發貨分散開。
- 待排卡片將訂單價格與工時控制器放在同一列，最晚發貨改為紅色時鐘行並允許完整換行。
- 日曆事件卡片改為固定分行顯示：訂單編號、`HH:mm~HH:mm` 起訖時間、工時長、最晚發貨標籤、日期、時間。
- 上傳 XLSX 區塊簡化為單一可點擊 / 拖拽的上傳面，週檢視表頭改為只顯示日號與星期。
- 週檢視左側時間軸標籤改為只顯示到分鐘。
- 待排工單卡片改為三列資訊：訂單編號 + 標記、訂單價格 + 工時控制、最晚發貨時間，並修正時鐘 icon 對齊。
- 拖曳 / resize 的超時提示提高到最上層，拖曳中的日曆工單鏡像改為更透明。
- 待排工單排序改為 `latest_ship_time ASC, urgent DESC, created_at ASC`，讓最晚發貨時間最靠近的工單優先顯示。
- 主介面改為固定在 `100dvh` 視口內，窄視口也不撐出整頁高度；待排列表與日曆內容用各自滾動區補足，側欄保留足夠高度供工單卡片操作。
- 週檢視日曆改為半小時可視區間，拖曳與 resize 仍保留 5 分鐘粒度。

## 2026-06-08 Email SMTP 設定

- 後端本機開發啟動會讀取根目錄 `.env`，Docker Compose 也會把同一份 `.env` 傳入 backend 容器。
- Email SMTP 改用 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASSWORD`、`SMTP_FROM`、`SMTP_AUTH`、`SMTP_USE_SSL`、`SMTP_USE_TLS` 設定。
- `SMTP_FROM` 會設定為實際寄件者；Email 收件者維持由前端 Dialog 必填輸入。
- Docker Compose 已移除 MailHog，寄信測試會直接使用 `.env` 指定的真實 SMTP。

## 2026-06-08 Email 週曆與重疊規則

- Email 週曆改為 5 分鐘 slot 加 `rowspan` 呈現，工單會依實際 `scheduledStart` 到 `scheduledEnd` 跨越正確時段。
- Email 週曆只輸出本週最早排程開始到最晚排程結束的時間範圍；沒有排程時顯示無資料列。
- 排程與取消完成時會阻擋未完成工單彼此重疊；已完成工單不參與重疊阻擋。
- 日曆事件 hover / focus 時會顯示 fixed tooltip，完整呈現被截斷的工單資訊，並避免被 FullCalendar 裁切。

## 2026-06-08 Docker PostgreSQL Port

- Docker Compose PostgreSQL 本機對外 port 改為 `15432`，避免與本機既有 PostgreSQL `5432` 衝突。
- Docker Compose PostgreSQL image 改為 `postgres:17`。
- PostgreSQL named volume 改為 `postgres17-data`，讓 PostgreSQL 17 以新的 demo 資料目錄啟動，避免沿用 PostgreSQL 16 volume。
- backend 容器仍透過 Compose service name 使用 `postgres:5432` 連線，容器內部資料庫 port 不變。
