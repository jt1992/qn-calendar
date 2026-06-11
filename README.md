# qn-calendar

XLSX 訂單匯入與工單排程系統。前端使用 Vue 3.5、Vite 8、Vue Router、Pinia、Axios 與 FullCalendar；後端使用 Spring Boot 3、Java 21、Spring Data JPA、Apache POI、Thymeleaf 與 JavaMailSender。

## 已完成功能

- XLSX 匯入支援欄名對應，不依欄位順序取值；目前支援 `訂單編號` / `订单编号`、`訂單價格` / `订单价格` / `买家实付金额`、`最晚發貨日期` / `应发货时间` 等欄位。
- 真實匯出 XLSX 可使用 `订单编号` 去重、`买家实付金额` 估算原始工時、`备注标签` 判斷加急、`买家留言` 與 `商家备注` 組合訂單備註。
- 匯入時會以 `order_no` 去重，純日期型最晚發貨日會轉為當天 `23:59:59`。
- `商家备注` 的發貨日期優先於 `应发货时间`；缺年時使用今年，缺年缺月時使用同列 `订单付款时间` 的月份，無法解析時回退 `应发货时间` 內的最早 `yyyy-MM-dd HH:mm前`。
- 訂單備註格式為 `买家留言：...`、`商家备注：...`，兩者皆無時寫入 `无任何备注`。
- 待排工單依 `latest_ship_time ASC, urgent DESC, created_at ASC` 排序。
- 待排卡片顯示訂單編號、狀態、加急、訂單價格、工時控制器與最晚發貨時間。
- 待排工單可用 +/- 以 15 分鐘為單位調整 `actualMinutes`。
- 週檢視與月檢視都支援拖曳排程；月檢視預設排到指定日期 `09:00:00`。
- 週檢視支援 15 分鐘粒度的拖曳與 resize，時間軸以半小時區間顯示。
- 排程片段必須符合 15 分鐘粒度，且結束時間不可超過最晚發貨時間。
- 日曆提供「允許過去」測試開關；開啟後可暫時排程到今天以前，方便測試歷史匯入資料。
- 不同訂單編號的工單不可重疊；完成工單仍保留在日曆中，也不可與其他訂單編號重疊。
- 拖曳或 resize 到其他訂單編號工單附近時，前端會自動貼齊到最近的可用相鄰時間，避免不小心重疊就直接回原位。
- 同一工單可拆分成多個日曆片段，片段可分布在不同天或不同時段。
- 同一工單片段在建立、移動或 resize 後若相鄰或重疊，後端會自動融合。
- 日曆事件卡片提供完成 / 取消完成、拆分片段操作；移出日曆統一用拖出日曆範圍完成。
- 日曆片段點完成時，若目前時間已超過片段結束時間，後端會把結束時間延長到目前時間並向上取 15 分鐘。
- 日曆事件拖出日曆範圍後放開會立即從日曆消失並回到待排工單，不播放回彈動畫。
- 工單從日曆移回待排後，待排卡片工時會恢復為原始預估時長。
- 移出單一片段時，若同一工單仍有其他片段，工單不回到待排；最後一段移出後才回到待排。
- 點擊任一片段時，同一工單所有日曆片段會顯示明顯外框，週檢視會標示最晚發貨紅線。
- 點擊日曆中非工單事件的空白區域時，會清除目前同訂單編號框選外框。
- 待排工單與日曆工單 hover 會顯示訂單備註。
- 日曆事件與 tooltip 顯示同一工單的總排程時長。
- 完成工單會半透明顯示。
- 前端上方導航欄以 Vue Router 在「待排工單」與「完工統計表」路由之間切換。
- 完工統計表只列出完成工單，顯示訂單編號、訂單備注、訂單價格、原本預估時長、實際總時長、差異時間與時薪。
- 完工統計表可顯示全部完成工單，或依訂單月份篩選；訂單月份來自匯入 XLSX 的訂單付款時間、訂單時間或下單時間欄位。
- 舊資料若缺少訂單時間，可重新匯入原始 XLSX；系統會跳過重複訂單但回填缺失的訂單時間。
- 訂單備註會顯示於完工統計表。
- 前端頂部導覽列提供全局 Email 入口，不再放在日曆工具列；Email Dialog 可選週表、月表或完工統計表。
- 週表 Email 可選日期範圍，依日期分組列出訂單編號、開始時間、結束時間、工時、發貨日期與備注；發貨日期以紅色醒目顯示，備注使用較大字級。
- 週表 Email 若日期範圍超過一週，會每 7 天拆成一段，段落之間保留間距並加入列印分頁樣式。
- 月表 Email 只需選擇月份，依日期分組列出訂單編號、開始時間、結束時間與工時。
- 完工統計表 Email 以訂單月份為基準，選擇月份後輸出前端完工統計表同欄位：訂單編號、訂單備注、訂單價格、原本預估時長、實際總時長、差異時間與時薪。
- 週表與月表 Email 都改為列印友善的緊湊 HTML table，避免舊週曆格狀版面留下過多空白。
- 前端支援 CSS 變數驅動的深色 / 淺色模式，預設深色模式。
- 本機與 Docker Compose 後端都會讀取根目錄 `.env` 的 SMTP 設定。
- 後端統一使用 SQLite；預設資料庫位置為 `~/.qn-calendar/qn-calendar.db`，可用 `QN_CALENDAR_DATA_DIR` 指定資料目錄。
- Maven package 會在後端打包時建置 Vue 前端，並把靜態檔放入 Spring Boot jar。
- Spring Boot 可直接服務 Vue production build，支援 SPA fallback，且不攔截 `/api/**`。
- 桌面版可透過 jpackage 產生 Windows `.exe` 與 macOS `.dmg`；啟動後可自動開瀏覽器，並在支援系統匣的環境提供「開啟頁面」與「關閉系統」。
- Docker Compose 改為單一後端服務，前端由 Spring Boot 提供，SQLite 資料保存在 Docker volume。
- 推送 `v*` tag 後，GitHub Actions 會分別在 Windows/macOS runner 用 jpackage 產生安裝檔並上傳到 GitHub Release。

## 常用指令

後端測試：

```bash
cd backend
mvn test
```

前端單獨打包：

```bash
cd frontend
npm run build
```

建立包含前端靜態檔的可執行 jar：

```bash
cd backend
mvn package
```

以桌面模式啟動 jar：

```bash
cd backend
java -Dapp.desktop.enabled=true -Djava.awt.headless=false -jar target/qn-calendar-backend-0.1.0.jar
```

Docker Compose：

```bash
cp .env.example .env
docker compose up --build
```

啟動後開啟 `http://localhost:8080`。Docker 模式會關閉桌面瀏覽器與系統匣功能，SQLite 資料存在 `qn-calendar-data` volume。

## 設定

`.env` 可設定 SMTP 與埠號。SQLite 預設使用使用者家目錄：

```properties
SERVER_PORT=8080
QN_CALENDAR_DATA_DIR=/absolute/path/to/qn-calendar-data
```

未設定 `QN_CALENDAR_DATA_DIR` 時，後端會使用 `~/.qn-calendar/qn-calendar.db`。

## 桌面版打包與發佈

jpackage 不能跨平台打包：Windows `.exe` 必須在 Windows runner/環境產生，macOS `.dmg` 必須在 macOS runner/環境產生。本專案已配置 `.github/workflows/release.yml`，推送 `v*` tag 後會自動建立 GitHub Release 並附上安裝檔。

```bash
git tag v1.0.0
git push origin v1.0.0
```

jpackage 要求 installer 版本第一段為正整數；若 tag 使用 `v0.x.x`，GitHub Actions 會把 installer 內部版本轉成對應的 `v1.x.x` 格式，但 Release tag 仍維持原本名稱。若 repository 維持私有，下載 GitHub Release 安裝檔的人仍需要對應的 GitHub 存取權限；要提供給沒有權限的使用者時，需要改用公開 release repo 或外部發佈管道。
