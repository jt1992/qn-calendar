# qn-calendar

XLSX 訂單匯入與工單排程系統。前端使用 Vue 3.5、Vite 8、Pinia、Axios 與 FullCalendar；後端使用 Spring Boot 3、Java 21、Spring Data JPA、Apache POI、Thymeleaf 與 JavaMailSender。

## 已完成功能

- XLSX 匯入支援必備中文欄位：`訂單編號`、`訂單價格`、`最晚發貨日期`。
- 匯入時會以 `order_no` 去重，純日期型最晚發貨日會轉為當天 `23:59:59`。
- 後端啟動時若資料庫沒有工單，會建立 demo 工單；可用 `APP_DEMO_DATA_ENABLED=false` 關閉。
- 待排工單依 `latest_ship_time ASC, urgent DESC, created_at ASC` 排序。
- 待排卡片顯示訂單編號、狀態、加急、訂單價格、工時控制器與最晚發貨時間。
- 待排工單可用 +/- 以 5 分鐘為單位調整 `actualMinutes`。
- 週檢視與月檢視都支援拖曳排程；月檢視預設排到指定日期 `09:00:00`。
- 週檢視支援 5 分鐘粒度的拖曳與 resize，時間軸以半小時區間顯示。
- 排程片段必須符合 5 分鐘粒度，且結束時間不可超過最晚發貨時間。
- 工單允許與其他工單重疊，完成工單也可與未完成工單重疊。
- 同一工單可拆分成多個日曆片段，片段可分布在不同天或不同時段。
- 同一工單片段在建立、移動或 resize 後若相鄰或重疊，後端會自動融合。
- 日曆事件卡片提供完成 / 取消完成、拆分片段、移出日曆操作。
- 移出單一片段時，若同一工單仍有其他片段，工單不回到待排；最後一段移出後才回到待排。
- 點擊任一片段時，同一工單所有日曆片段會顯示明顯外框，週檢視會標示最晚發貨紅線。
- 日曆事件與 tooltip 顯示同一工單的總排程時長。
- 完成工單會半透明顯示。
- 前端提供 Email Dialog，日期預設為目前日曆焦點週的週起訖。
- Email 週曆使用 5 分鐘 slot 與 `rowspan` 輸出 HTML，並依片段資料分配重疊 lane。
- 前端支援 CSS 變數驅動的深色 / 淺色模式，預設深色模式。
- 本機與 Docker Compose 後端都會讀取根目錄 `.env` 的 SMTP 設定。
- Docker Compose PostgreSQL 使用 `postgres:17`，本機對外 port 為 `15432`。

## 常用指令

```bash
cd backend
mvn test
```

```bash
cd frontend
npm run build
```
