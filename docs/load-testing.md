# V7 壓測與測量範圍

## 實驗隔離

V1 僅活動 1。新版預設活動 2；每次比較版本或負載必須換一個乾淨活動，不能僅重設剩餘量而留下 reservations。

```bash
docker compose exec -T db psql -U ticketing -d ticketing -c 'INSERT INTO tickets(event_id,total_stock,remaining) VALUES(1001,100,100);'
mkdir -p load-test/results
docker compose run --rm --user "$(id -u):$(id -g)" k6 run -e EVENT_ID=1001 -e REQUESTS=1000 -e VUS=100 /scripts/final.js
```

`summary.json` 每次覆寫，測試後改名保存；資料夾已排除 Git，避免把實測輸出或環境資訊意外推送。

## 比較 V2／V3／最終流程

每種 stage 使用不同新活動。`STAGE=atomic` 走 V2，同步建單；`STAGE=redis` 走 V3；`STAGE=final`（預設）走非同步建單。回應時間的業務意義不同，不能拿 202 admission 延遲直接當成完整訂單／付款延遲。

```bash
docker compose run --rm --user "$(id -u):$(id -g)" k6 run -e EVENT_ID=1002 -e STAGE=atomic /scripts/final.js
```

執行前須先建立 1002。這份測試預設從 100 張且無活躍 reservation 開始，不執行付款，不主動釋放票；預設 TTL 300 秒、maxDuration 240 秒，避免測試中重用到期票。

## 10 萬次請求目標（已於本機完成）

先建立新活動 1003，再執行：

```bash
docker compose run --rm --user "$(id -u):$(id -g)" k6 run -e EVENT_ID=1003 -e REQUESTS=100000 -e VUS=1000 /scripts/final.js
```

這是 **1000 個 VUs 共送 100000 次 admission 請求**，不是 100000 個同時連線、不是 100000 RPS，也不是 100000 張成功付款訂單。僅有 100 張庫存，多數請求預期是業務 409。若超過情境時限、非預期錯誤或未滿足 thresholds，測試應失敗，不應移除門檻後宣稱達標。

## 指標與通過條件

- 完整 iterations 數等於 REQUESTS。
- `accepted_reservations=100`；不允許因到期釋放而在此 admission 實驗重用票。
- `unexpected_responses=0`，HTTP transport／server failure 為 0；409 被分類為預期業務結果。
- admission_latency_ms 提供 avg、P95、P99；http_reqs.rate 是包含 setup／teardown 稽核的 HTTP 吞吐量，不是成功付款 TPS。
- 最終 audit 的 oversold_units=0、duplicate_orders=0、discrepancy=0。
- 使用 admin audit 驗證 DB 帳本；不能僅靠 HTTP 200／202 數或 Redis counter。

此檔案量測 admission，不等待 Kafka 建單或付款完成。完整業務及故障驗收由 FinalIntegrationTest 進行；另已加入 `load-test/journey.js` 執行非同步建單 → 付款 → 預約／付款重放，並產生 `paid_journey_ms` 的 P95／P99。這份完整旅程壓測已完成 100 VUs／1000 iterations，100 筆付款成功；P95 10.898 秒，詳見 [基線結果](k6-baseline.md)。

## 完整旅程壓測

先建立新的獨立活動 1004，再執行：

```bash
docker compose run --rm --user "$(id -u):$(id -g)" k6 run -e EVENT_ID=1004 /scripts/journey.js
```

門檻要求剛好 100 筆成功付款、100 筆接受的預約，0 journey_errors／integrity_errors，最終 paid=held=100、remaining=0、discrepancy=0。每筆接受的請求會重放同 key 預約，等待 Kafka 最多 60 秒，付款後再次送成功與失敗付款，驗證同一已付款訂單始終保持 PAID。

`paid_journey_ms` 從原始 POST 前開始，到首次付款成功為止，含預約重放與 Consumer 等待；之後的付款重放不包含在這個延遲內。HTTP 請求總數會大於 iterations。100 張票以外的請求預期回 409，不能把它們算成成功付款 TPS。完整報告輸出 `/results/journey-summary.json`，由宿主資料夾保存。

## 已有實測與尚未測量

2026-10-28 V1：1000 VUs／1000 POST，成功 1000，初始 100，最後 46，實際扣 54；HTTP p95 5.48s，這代表競態缺陷。

最終流程已完成 admission 與付款旅程的 100 VUs／1000 iterations 本機基線，見 [基線結果](k6-baseline.md)。最終流程另已完成 1000 VUs、admission 與完整旅程各 10 萬次迭代，所有 thresholds 通過，見 [10 萬次結果](k6-100k.md)。V2／V3 性能對照、負載中的故障恢復時間、可持續付款 TPS 仍未量測。不得把 V1 數據沿用為新版性能或正確性佐證。
