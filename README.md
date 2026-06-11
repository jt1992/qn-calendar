# qn-calendar

XLSX 订单导入与工单排程系统。前端使用 Vue 3.5、Vite 8、Vue Router、Pinia、Axios 与 FullCalendar；后端使用 Spring Boot 3、Java 21、Spring Data JPA、Apache POI、Thymeleaf 与 JavaMailSender。

## 已完成功能

- XLSX 导入支持列名对应，不依字段顺序取值；目前兼容繁简列名，例如订单编号、订单价格、买家实付金额、最晚发货日期、应发货时间等字段。
- 系统用户可见中文文案统一使用简体中文；XLSX 导入会继续兼容繁体列名与旧格式数据。
- 真实导出 XLSX 可使用 `订单编号` 去重、`买家实付金额` 估算原始工时、`备注标签` 判断加急、`买家留言` 与 `商家备注` 组合订单备注。
- 顶部导航提供全局设置，可调整预估工时基础金额；默认每 100 元换算为 1 小时，保存后写入 SQLite，并影响后续导入新订单的预估工时。
- 导入时会以 `order_no` 去重，纯日期型最晚发货日会转为当天 `23:59:59`。
- `商家备注` 的发货日期优先于 `应发货时间`；缺年时使用今年，缺年缺月时使用同列 `订单付款时间` 的月份，无法解析时回退 `应发货时间` 内的最早 `yyyy-MM-dd HH:mm前`。
- 订单备注格式为 `买家留言：...`、`商家备注：...`，两者皆无时写入 `无任何备注`。
- 待排工单依 `latest_ship_time ASC, urgent DESC, created_at ASC` 排序。
- 待排卡片显示订单编号、状态、加急、订单价格、工时控制器与最晚发货时间。
- 待排工单可用 +/- 以 15 分钟为单位调整 `actualMinutes`。
- 周视图与月视图都支持拖拽排程；月视图默认排到指定日期 `09:00:00`。
- 周视图支持 15 分钟粒度的拖拽与 resize，时间轴以半小时区间显示。
- 排程片段必须符合 15 分钟粒度，且结束时间不可超过最晚发货时间。
- 日历提供「允许过去」测试开关；打开后可暂时排程到今天以前，方便测试历史导入数据。
- 不同订单编号的工单不可重叠；完成工单仍保留在日历中，也不可与其他订单编号重叠。
- 拖拽或 resize 到其他订单编号工单附近时，前端会自动贴齐到最近的可用相邻时间，避免不小心重叠就直接回原位。
- 同一工单可拆分成多个日历片段，片段可分布在不同天或不同时段。
- 同一工单片段在创建、移动或 resize 后若相邻或重叠，后端会自动融合。
- 日历事件卡片提供完成 / 取消完成、拆分片段操作；移出日历统一用拖出日历范围完成。
- 日历片段点完成时，若目前时间已超过片段结束时间，后端会把结束时间延长到目前时间并向上取 15 分钟。
- 日历事件拖出日历范围后放开会立即从日历消失并回到待排工单，不播放回弹动画。
- 工单从日历移回待排后，待排卡片工时会恢复为原始预估时长。
- 移出单一片段时，若同一工单仍有其他片段，工单不回到待排；最后一段移出后才回到待排。
- 点击任一片段时，同一工单所有日历片段会显示明显外框，周视图会标示最晚发货红线。
- 点击日历中非工单事件的空白区域时，会清除目前同订单编号框选外框。
- 待排工单与日历工单 hover 会显示订单备注。
- 日历事件与 tooltip 显示同一工单的总排程时长。
- 完成工单会半透明显示。
- 前端上方导航栏以 Vue Router 在「待排工单」与「完工统计表」路由之间切换。
- 完工统计表只列出完成工单，显示订单编号、订单备注、订单价格、原本预估时长、实际总时长、差异时间与时薪。
- 完工统计表可显示全部完成工单，或依订单月份筛选；订单月份来自导入 XLSX 的订单付款时间、订单时间或下单时间字段。
- 旧数据若缺少订单时间，可重新导入原始 XLSX；系统会跳过重复订单但回填缺失的订单时间。
- 订单备注会显示于完工统计表。
- 完工统计表的「原本预估时长」与「差异时间」来自工单保存的预估工时；调整全局基础金额后，新导入订单会使用新基础金额。时薪维持按 `订单价格 / 实际总时长` 计算。
- 前端顶部导航列提供全局 Email 入口，不再放在日历工具列；Email Dialog 可选周表、月表或完工统计表，发送成功提示显示在 Dialog 发送按钮左侧。
- 周表 Email 可选日期范围，并以 A4 横向 PDF 附件输出带左侧时间轴的周视图，显示订单编号、开始 / 结束时间、最晚发货时间（红色）与备注；时间轴会按该周工单的最早开始与最晚结束自动裁切前后空白时段。
- 周表 Email 若日期范围超过一周，会每 7 天拆成一页 PDF，段落之间保留间距，A4 横向打印时至少可完整显示一周。
- 月表 Email 只需选择月份，并以 A4 横向 PDF 附件输出紧凑月历表，显示订单编号、开始 / 结束时间与最晚发货时间（红色）；无后续排程时会截断空白周并提示暂无排程日期。
- 完工统计表 Email 以订单月份为基准，选择月份后以 A4 横向 PDF 附件输出前端完工统计表同字段：订单编号、订单备注、订单价格、原本预估时长、实际总时长、差异时间与时薪。
- Email 后端仍使用 HTML 模板生成 PDF，但邮件正文不再发送 HTML 内容，只保留 PDF 附件；完工统计表 PDF 会铺满可打印宽度。
- PDF 顶部信息保持精简：完工统计表只显示 `完工统计表｜月份｜笔数`，月排程表只显示 `月排程表 ｜ 月份`，周排程表直接从时间轴表格开始。
- Email Dialog 会按选择自动生成只读主题：周表为 `周排程表 - 开始日期 - 结束日期`，月表为 `月排程表 - 年月`，完工统计为 `完工统计表 - 订单月份` 或 `完工统计表 - 全部`。
- PDF 附件文件名使用 UTF-8 `filename*` MIME 参数编码，避免中文附件名在邮件客户端显示为问号。
- 周表与月表 PDF 都使用打印友善的紧凑日历 table，避免格子高度与无排程日期产生多余空白页。
- 前端支持 CSS 变量驱动的深色 / 浅色模式，默认深色模式。
- 本机与 Docker Compose 后端都会读取根目录 `.env` 的 SMTP 设定。
- 后端统一使用 SQLite；默认数据库位置为 `~/.qn-calendar/qn-calendar.db`，可用 `QN_CALENDAR_DATA_DIR` 指定数据目录。
- Maven package 会在后端打包时构建 Vue 前端，并把静态文件放入 Spring Boot jar。
- Spring Boot 可直接服务 Vue production build，支持 SPA fallback，且不拦截 `/api/**`。
- 桌面版可通过 jpackage 生成 Windows `.exe` 与 macOS `.dmg`；启动后可自动开浏览器，并在支持系统托盘的环境提供「打开页面」与「关闭系统」。
- Docker Compose 改为单一后端服务，前端由 Spring Boot 提供，SQLite 数据保存在 Docker volume。
- 推送 `v*` tag 后，GitHub Actions 会分别在 Windows/macOS runner 用 jpackage 生成安装文件并上传到 GitHub Release。

## 常用指令

后端测试：

```bash
cd backend
mvn test
```

前端单独打包：

```bash
cd frontend
npm run build
```

创建包含前端静态文件的可执行 jar：

```bash
cd backend
mvn package
```

以桌面模式启动 jar：

```bash
cd backend
java -Dapp.desktop.enabled=true -Djava.awt.headless=false -jar target/qn-calendar-backend-0.1.0.jar
```

Docker Compose：

```bash
cp .env.example .env
docker compose up --build
```

启动后打开 `http://localhost:8080`。Docker 模式会关闭桌面浏览器与系统托盘功能，SQLite 数据存在 `qn-calendar-data` volume。

## 设定

应用内「全局设置」可调整预估工时基础金额。该值保存在 SQLite 中，默认值为 `100`，代表每 100 元换算为 1 小时预估工时。

`.env` 可设定 SMTP 与端口号。SQLite 默认使用用户家目录：

```properties
SERVER_PORT=8080
QN_CALENDAR_DATA_DIR=/absolute/path/to/qn-calendar-data
```

未设定 `QN_CALENDAR_DATA_DIR` 时，后端会使用 `~/.qn-calendar/qn-calendar.db`。

## 桌面版打包与发布

jpackage 不能跨平台打包：Windows `.exe` 必须在 Windows runner/环境生成，macOS `.dmg` 必须在 macOS runner/环境生成。本项目已配置 `.github/workflows/release.yml`，推送 `v*` tag 后会自动创建 GitHub Release 并附上安装文件。

```bash
git tag v1.0.0
git push origin v1.0.0
```

jpackage 要求 installer 版本第一段为正整数；若 tag 使用 `v0.x.x`，GitHub Actions 会把 installer 内部版本转成对应的 `v1.x.x` 格式，但 Release tag 仍维持原本名称。若 repository 维持私有，下载 GitHub Release 安装文件的人仍需要对应的 GitHub 访问权限；要提供给没有权限的用户时，需要改用公开 release repo 或外部发布渠道。
