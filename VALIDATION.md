# 驗證狀態 · 2026-10-30

**Java 完整編譯、封裝、整合測試及 Compose + k6 本機 1000 次基線驗收已通過。1000 VUs 下兩份壓測各完成 10 萬次迭代，全部 thresholds 通過。**

## 已核對的執行證據

使用者在本機終端執行 `mvn -B verify`，於 2026-10-30 02:51（Asia/Taipei）完成，輸出 BUILD SUCCESS。助理另外讀取同一專案 target/surefire-reports 的兩份 XML，並確認已封裝的 Spring Boot JAR 存在。

| 項目 | 結果 | 來源 |
| --- | --- | --- |
| V1 整合測試 | 2 tests，0 failures/errors/skipped | TEST-com.ticketing.TicketIntegrationTest.xml |
| 新版整合測試 | 15 tests，0 failures/errors/skipped，82.142 秒 | TEST-com.ticketing.FinalIntegrationTest.xml |
| 根專案整體 | 17 tests，0 failures/errors/skipped；BUILD SUCCESS | 使用者終端輸出 + 本機 Surefire XML |
| 核心單元測試 | 8 tests，0 failures/errors/skipped | verification/core 的 Surefire XML；DB/cache 是 mock |
| Java 封裝 | 可執行 Spring Boot JAR 建立成功 | target/high-concurrency-ticketing-system-0.0.1-SNAPSHOT.jar |
| 靜態檢查 | Compose config、Maven XML、OpenAPI refs、文件連結、k6 JS／shell 語法通過 | 本機檢查 |

15 個新版整合案例使用真實 PostgreSQL、Redis 與 Kafka Testcontainers，涵蓋 Atomic SQL 併發不超賣、同 key 不重複建單、輸入與 admin 邊界、Lua 原子扣減、Kafka 重複訊息、付款重放、失敗退款補票、排隊／待付款到期、付款與 expiry 競爭、等待 row lock 跨越付款期限、毒訊息 DLQ、資料修復、Kafka／Redis 中斷恢復。

使用者貼出的 Netty／Kafka 斷線尾端日誌沒有使測試失敗；因開頭例外缺失，不單靠片段判定每一條警告的來源。通過與否依 Surefire 結果與 Maven exit/result 判斷，不以日誌中是否出現 ERROR 字樣判斷。

## 本機負載基線已完成

使用者執行 load-only 驗收後，保存的兩份 JSON 已由助理讀取核對，所有 thresholds 為 true：

- admission：100 VUs、1000 次預約，100 接受、900 業務拒絕，超賣／重複訂單／庫存差異皆 0，HTTP 失敗率 0%。P95=548.90ms、P99=800.35ms。
- 完整旅程：100 VUs、1000 iterations，100 筆付款成功；預約重放、Kafka 建單、付款、付款重放檢查全部通過，601 checks／0 失敗。P95=10.898s、P99=10.907s（只計成功付款的 100 筆）。
- 最終 paid=held=100、remaining=0；integrity_errors=0。
- Compose 啟動已由實際進入兩份容器 k6 與完成驗收腳本證實。硬體與容器建置的完整 log 沒有另行保存，不虛構詳盡環境資訊。

詳見 [基線結果](docs/k6-baseline.md) 與 [原始報告摘要／SHA-256](docs/k6-baseline-results.json)。

## 10 萬次目標已完成

使用者實跑後，助理核對 event-1793300559.json 與 journey-1793300560.json：

- 每份 1000 VUs、100000 iterations、無中斷，全部 thresholds 通過。
- admission：100 接受、99900 業務拒絕；超賣／重複訂單／庫存差異皆 0；HTTP 失敗率 0%。P95 857.25ms、P99 1190.32ms；全程 35.731 秒。
- journey：100 筆付款成功，0 journey/integrity errors，601 checks 通過；有效付款旅程 P95 12.644s、P99 12.873s；全程 29.167 秒。
- 最終 paid=held=100、remaining=0、discrepancy=0、duplicate_orders=0，由 teardown 檢查通過佐證。

V1–V8 的本機功能、Compose 與既定負載驗收現已通過。這不是生產認證，仍受以下範圍限制。詳見 [10 萬次報告](docs/k6-100k.md) 與 [機器可讀證據](docs/k6-100k-results.json)。

## 重現測試

在同一專案目錄執行：

```bash
bash scripts/acceptance.sh --load-only
```

此模式核對本機 Java 成功證據與來源時間後，啟動 Compose，再以不同的新活動執行 admission 與 Kafka→付款完整旅程兩份 k6 壓測。來源改動後不能直接沿用舊報告；檢查不符時應跑完整：

```bash
bash scripts/acceptance.sh
```

報告保存在 load-test/results；檔名分別為 event-ID.json 與 journey-ID.json。

## 尚未完成／不得宣稱的結果

- 長時間穩態、固定到達率、V2／V3 性能對照尚未測量；現有結果只代表上述短測條件。
- 已完成 10 萬次嘗試，但每活動只接受／付款 100 張；10 萬筆成功訂單及可持續付款 TPS 未測。
- 故障恢復在整合案例已驗證功能，未取得負載下的恢復時間或多機 HA 數據。

助理所在工作階段仍無法存取 Docker socket，下載新依賴也受限；提升權限被自動核准機制拒絕。這與使用者本機終端可成功執行測試是不同權限環境，並非 Docker 未啟動。上列 Java 與 k6 成功結果來自使用者實跑，已用共享檔案中的 XML／JSON 核對。

已完成的 10 萬次測試重現指令：`REQUESTS=100000 VUS=1000 bash scripts/acceptance.sh --load-only`。此命令會對 admission 和旅程各執行 10 萬次迭代，仍只有每活動 100 張票，並不是 10 萬筆成功訂單。

機器可讀狀態：[verification-results.json](docs/verification-results.json)。

## 歷史 V1 負載證據

2026-10-28 V1：1000 VUs／1000 POST 成功 1000，庫存 100→46，只扣 54，重現 lost update。這是舊版缺陷實驗，不能當成新版的正確性或容量結果。見 [V1 validation](docs/v1-validation.md)。
