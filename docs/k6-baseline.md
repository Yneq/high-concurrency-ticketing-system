# k6 本機基線結果 · 2026-10-30

使用者在本機終端執行 `bash scripts/acceptance.sh --load-only`，Compose 啟動與兩份 k6 均完成；助理讀取保存的原始 JSON 核對所有 thresholds 為 true。這是 **100 VUs、每份 1000 iterations、各有 100 張初始票** 的兩次獨立短測，不是 10 萬次請求，也不是持續容量保證。

## 預約 admission

原始報告 `load-test/results/event-1793300285.json`，事件 1793300285。

| 指標 | 實測 |
| --- | ---: |
| 預約 iterations | 1000 |
| 接受預約 | 100 |
| 業務售罄／閘門拒絕 | 900 |
| 非預期回應／HTTP 失敗率 | 0 / 0% |
| 超賣／重複訂單／庫存差異 | 0 / 0 / 0 |
| admission 平均 | 155.28 ms |
| admission P95 | 548.90 ms |
| admission P99 | 800.35 ms |
| 預約 iterations rate | 543.89 /s |
| HTTP rate（含 setup/teardown） | 545.52 /s |
| 全程（含 setup/teardown） | 1.839 s |

此吞吐量包含 900 次預期 409，不是 544 筆成功訂單 TPS。HTTP 總數 1003，包含初始化與最終稽核。

## Kafka → 訂單 → 模擬付款 → 重放

原始報告 `load-test/results/journey-1793300286.json`，事件 1793300286。

| 指標 | 實測 |
| --- | ---: |
| 旅程 iterations | 1000 |
| 接受預約／付款成功 | 100 / 100 |
| journey_errors／integrity_errors | 0 / 0 |
| HTTP 失敗率 | 0%（5019 次 HTTP） |
| 有效已付款旅程平均 | 8.348 s |
| 有效已付款旅程 P95 | 10.898 s |
| 有效已付款旅程 P99 | 10.907 s |
| 全部 checks | 601 通過，0 失敗 |
| 全程（含 setup/teardown） | 11.058 s |

100 筆預約重放均保持同一 ID；100 筆 Consumer 建單與付款成功；另外 200 次重複成功／晚到失敗付款檢查均保持 PAID。最終使用者控制台稽核為 paid=held=100、remaining=0、discrepancy=0、duplicate_orders=0；保存報告中的 integrity threshold 也通過。

paid_journey_ms 只統計 100 筆接受並付款成功的旅程，從原始 POST 前開始，包含預約重放與等待 Kafka，到首次付款成功為止；之後的付款重放不包含在此延遲。其他售罄迭代不是付款旅程樣本。約 10.9 秒的尾端旅程延遲仍需進一步量測與優化，不能僅以 admission 延遲代表整體購票體驗。

## 證據與限制

[機器可讀結果與原始報告 SHA-256](k6-baseline-results.json)。原始報告保留於本機 load-test/results，該目錄不納入 Git。

- V1 的 1000 VUs 與本次 100 VUs 不同，不能直接比較數值並宣稱性能提升倍數。
- 測試是 closed-loop shared-iterations，不是固定到達率 RPS 測試。
- 原始報告未完整記錄 CPU、記憶體與其他背景負載，因此這不是可重現硬體容量證明。
- 整合測試驗證故障恢復功能；本次 k6 沒有在負載中注入故障。
- 此處只保存 1000 次基線；後續 10 萬次結果見 [另份報告](k6-100k.md)。長時間穩態、跨機 HA 仍未測量。
