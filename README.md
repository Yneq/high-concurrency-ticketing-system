# High-Concurrency Ticketing System — Phase 1

Java 21 / Spring Boot 3.5.16 / Spring Data JPA / PostgreSQL 16 / Docker Compose / k6。

這是一個**刻意存在 concurrency race condition 的教學實驗**，目的是親眼觀察大量請求直接存取資料庫時的 lost update 與超賣。請只用於本機實驗。這一階段不加入 Redis、Kafka、鎖、版本控制、身分驗證或冪等機制。

## 架構與競態

```text
k6：1000 VUs，每人一次 POST
          ↓
Spring Boot / TicketController
          ↓
@Transactional TicketService
SELECT → 檢查 remaining > 0 → 延遲 50ms → remaining - 1 → UPDATE
          ↓
PostgreSQL / tickets：event_id=1，初始 100 張
```

`@Transactional` 保證單次交易完整性，卻不會自動將「讀取→判斷→寫回」變成不可分割操作。沒有 `@Version` 或顯式鎖時，JPA 將記憶體內計算出的庫存值寫回：

```text
A 讀到 100；B 讀到 100
A 寫入 99，成功；B 也寫入 99，成功
成功 2 次，資料庫卻只少 1 張
```

因此庫存不一定為負數；即使有 `remaining >= 0` 約束也無法避免這種超賣。預設 50ms 教學延遲放大重現機率；`RACE_WINDOW_MS=0` 只是取消延遲，不會修好競態。交易、執行緒與連線排程會影響每次結果，不保證固定成功數。

這個 Phase 沒有訂單／預約紀錄表，每個 HTTP 200 代表一次成功承諾，以 k6 計數觀察超賣。回應中的 remaining 是該交易寫入值，可能立即被其他交易覆寫。

## 啟動

安裝並啟動 Docker Desktop 或相容的 Docker 引擎（含 Compose v2）。全容器模式不需本機 Java、Maven 或 k6。

在本專案目錄執行：

```bash
docker compose up --build -d db app
docker compose logs -f app
```

看到 `Started TicketingApplication` 後離開日誌畫面，再查詢：

```bash
curl -i http://localhost:8080/api/tickets/1
```

預期 HTTP 200：

```json
{"eventId":1,"totalStock":100,"remaining":100}
```

應用啟動時執行 `schema.sql` 與 `data.sql`，建立 tickets 表及一筆活動庫存：**活動 1，共 100 張**，不是 100 個不同活動。重啟使用 `ON CONFLICT DO NOTHING`，保留既有庫存，不會補回 100 張。資料存在 Compose 的 PostgreSQL volume。

若本機 5432 已被其他 PostgreSQL 佔用，可改用：

```bash
POSTGRES_PORT=5433 docker compose up --build -d db app
```

容器內 API 仍連到 db:5432；若改用本機 Java，請另設 `DB_URL=jdbc:postgresql://localhost:5433/ticketing`。後續 Compose 操作可持續帶入 `POSTGRES_PORT=5433`，避免重建時恢復預設埠。

若 8080 也被佔用，可加上 `APP_PORT=18080`，例如 `POSTGRES_PORT=5433 APP_PORT=18080 docker compose up -d db app`，此時從主機使用 `http://localhost:18080`。Compose k6 仍直接連容器 app:8080。

## API

| 方法 | 路徑 | 結果 |
| --- | --- | --- |
| GET | `/api/tickets/{eventId}` | 200：eventId、totalStock、remaining |
| POST | `/api/tickets/{eventId}/reserve` | 200：eventId、status=RESERVED、remaining |

POST 不需 request body，每次預約一張。售罄為 HTTP 409 (`SOLD_OUT`)，不存在活動為 404 (`EVENT_NOT_FOUND`)，eventId 非整數為 400。

```bash
curl -i -X POST http://localhost:8080/api/tickets/1/reserve
```

單次預約預期 remaining=99。試過此命令後，壓測前需重設。

## 1000 個併發請求壓測

先確認沒有其他測試仍在執行，再重設測試庫存（這會覆寫活動 1 的實驗結果）：

```bash
docker compose exec -T db psql -U ticketing -d ticketing -c 'UPDATE tickets SET total_stock = 100, remaining = 100 WHERE event_id = 1;'
docker compose run --rm k6
```

`load-test/reserve.js` 使用 `per-vu-iterations`，1000 個 VUs 各發一次 POST，共 1000 次預約請求。setup 檢查初始庫存必須為 100/100，並設定共同目標時間（10 秒後）減少啟動偏差。這是 1000 個用戶端併發的 burst，並不表示伺服器同時執行 1000 個 DB 交易；Tomcat 最多 200 執行緒、連線池 20 條，其他請求會排隊。GET 前後檢查另計，所以 HTTP 請求總數會高於 1000。

檢查 k6 輸出：

- `iterations = 1000`：1000 次預約迭代完整結束。
- `reservation_successes`：200 且 status=RESERVED 的數量。**大於 100 就直接證明超賣**。
- `sold_out`：409 的數量，屬於可接受業務結果，不算 HTTP 失敗。
- `unexpected_responses = 0`：沒有非預期回應或內容。
- 最後日誌顯示 initial、final、DB decrements。成功數大於 `100 - final.remaining` 代表 lost update，即使成功數尚未超過 100。

例如「成功 1000 次、最後剩 50」代表承諾了 1000 張，但資料庫僅扣掉 50 張；此例只是說明，實際值依機器而異。

這份壓測的 threshold **刻意要求成功數 >100**；綠燈代表重現缺陷，不代表系統正確。未重現、漏跑或非預期錯誤會使 k6 失敗，請檢查日誌與資源，重設後重跑；可將延遲加大：

```bash
RACE_WINDOW_MS=200 docker compose up -d app
```

大量併發可能觸及本機檔案描述符或資源限制。連線錯誤、timeout 不能當成超賣證據；需先排除環境問題。請勿同時執行多份壓測或手動預約。

若已安裝本機 k6：

```bash
k6 run load-test/reserve.js
# 其他地址或活動（該活動仍須有 100/100 庫存）
k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=1 load-test/reserve.js
```

直接檢查 DB：

```bash
docker compose exec -T db psql -U ticketing -d ticketing -c 'SELECT * FROM tickets;'
```

## 本機 Java 開發

需要 JDK 21、Maven 3.9+、可用的 Docker 引擎。僅啟動資料庫，讓本機 API 使用 8080：

```bash
docker compose stop app
docker compose up -d db
mvn spring-boot:run
```

資料庫預設 localhost:5432/ticketing，帳密 ticketing/ticketing。可用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`RACE_WINDOW_MS` 覆寫。本機 API 可使用本機 k6，Compose k6 的 BASE_URL 預設指向容器 app。

## 測試與建置

```bash
mvn test
mvn package
```

`TicketIntegrationTest` 使用 Testcontainers 的獨立 PostgreSQL，驗證初始化、查詢、單次扣庫存、404、409，以及 20 個同時預約造成的 lost update。併發測試使用共同起跑點與 200ms 延遲提高可重現性，仍受排程影響。沒有 Docker 時兩個整合測試會**跳過**，不表示它們通過；請檢查 Maven 的 Skipped 數量。

## 停止與清理

```bash
docker compose down
```

此操作保留 volume。若要刪除本專案全部資料庫資料並重新初始化：

```bash
docker compose down -v
docker compose up --build -d db app
```

## 專案結構

```text
src/main/java/com/ticketing/
  controller/   HTTP API
  service/      故意有競態的交易流程
  repository/   Spring Data JPA
  entity/       tickets 映射（無 @Version）
  dto/          API 回應
src/main/resources/  設定、schema.sql、data.sql
src/test/            真實 PostgreSQL 整合測試
load-test/reserve.js 1000 VUs 一次性壓測
compose.yaml         app、db、k6
Dockerfile           Java 21 多階段建置
```

版本依據：[Spring Boot 3.5 系統需求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)（3.5.16 支援 Java 21）。

## 實測結果

已完成 1000 VUs 壓測：1000 次成功，最終庫存 46，資料庫僅扣掉 54 張，明確重現 lost update 與超賣。所有實驗 thresholds 通過。完整數據與本次使用的埠號見 [VALIDATION.md](VALIDATION.md)。
