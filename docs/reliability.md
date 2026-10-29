# 可靠性、補償與限制

## 不變條件

1. PostgreSQL 每次成功 UPDATE 必須滿足 remaining>0，扣一次一張；剩餘量不能負數。
2. 全域 Idempotency-Key 綁定 event/user；同 key 同 payload 重放同一 reservation，即使已終態也不重新扣票。不同 payload 409。
3. orders.id 是 reservation FK/PK；重複訊息不重複建單。
4. QUEUED 或 PENDING_PAYMENT 才能轉成 EXPIRED／FAILED，CAS 成功才加回一張。
5. `remaining + active_or_paid_reservations = total_stock`。這是稽核條件，不以 Redis counter 作唯一證據。

## 故障恢復

| 故障窗口 | 處理 |
| --- | --- |
| Redis 缺失／逾時 | 500ms timeout 後回退 DB 原子扣票；可能增加 DB 競爭 |
| Redis 已扣、DB 交易失敗 | 嘗試 Lua release；失敗時靠週期 DB 投影修復 |
| DB commit 前 app 崩潰 | 交易回滾，不留下半筆 reservation／outbox |
| DB commit 後、回應前 app 崩潰 | 客戶端以相同 key 重送，取得同一 ID；不重複扣票 |
| Kafka 不可用 | 未送出的 outbox 留存；週期再送；排隊超過 TTL 則釋放 |
| Kafka ack 後 publisher 崩潰 | 可能重送；Consumer 列鎖 + PK／狀態去重 |
| Consumer commit 後 offset ack 前崩潰 | 重新消費時看終態／已有訂單，不重複建立 |
| Consumer 連續錯誤 | DefaultErrorHandler 最多重試兩次後進 DLT；DLT 發送失敗不視為恢復成功 |
| DLT Consumer DB 不可用 | 寫入失敗不正常 ack；框架會依錯誤處理重送，DLT 本身採預設容器錯誤處理以避免遞迴轉送 DLT |
| 付款與 expiry 同時執行 | 同 reservation row lock；依 DB 時間判斷到期，終態不重複退款／補票 |
| 缺漏 expiry 執行 | DB 儲存 expires_at，不依賴記憶體 timer，重啟後繼續掃描 |

Redis 投影每 5 秒從 DB 重建 stock，stock key TTL 15 秒。投影與 admission 不是分散式交易，可能與流量競爭而高估／低估快取庫存；高估由 PG 擋住不超賣，低估會短暫回 409。token set 僅用於短時間 Lua 去重（120 秒），真正冪等依靠 DB。此版本優先正確性與可恢復性，沒有宣稱快取強一致或完全無誤拒。

## 修復與 DLQ

```bash
curl -H 'X-Admin-Token: local-demo-admin' http://localhost:18080/api/admin/events/2/audit
curl -X POST -H 'X-Admin-Token: local-demo-admin' http://localhost:18080/api/admin/events/2/reconcile
curl -H 'X-Admin-Token: local-demo-admin' http://localhost:18080/api/admin/dead-letters
curl -X POST -H 'X-Admin-Token: local-demo-admin' http://localhost:18080/api/admin/dead-letters/UUID/replay
```

repair 先鎖 tickets 列，再以活躍／已付款 reservation 數計算剩餘量。若 held 已超出總量，拒絕自動修正並要求調查，不憑空刪單。Redis 定期自動修正；DB 帳本校正刻意保留為 admin 操作，避免把人工資料錯誤靜默抹除。修復不保證能找回被外部刪除的 reservation，正式系統還需不可變流水／備份。

DLQ 儲存 payload 與通用錯誤訊息；原始 exception headers 在 Kafka DLT 中，詳細原因查 broker headers／app logs。本版沒有完整 header 的 DB 存檔或後台 UI。非法 UUID payload 不能直接 replay，需先修正資料／程式與人工調查；未知 reservation 不能藉 replay 建立無庫存訂單。

付款／建單在取得 row lock 後使用 PostgreSQL `clock_timestamp()` 判斷期限，避免交易開始於到期前、等待鎖後卻繼續沿用舊的 `CURRENT_TIMESTAMP` 而接受晚到付款。

## 本機故障演練

在獨立活動上執行，避免與壓測共用：

```bash
docker compose stop kafka
# 發預約仍應持久化為 QUEUED；超過 TTL 就過期釋放。
docker compose start kafka
# 未過期的 outbox 訊息應恢復建單；過期訊息不得重建有效訂單。
docker compose stop redis
# 新預約改走 DB atomic UPDATE。
docker compose start redis
# 等待投影恢復，檢查 audit discrepancy=0。
docker compose restart app
# 相同 Idempotency-Key 重送，確認 id 不變。
```

FinalIntegrationTest 另有 broker／Redis pause→unpause 的自動案例。FinalIntegrationTest 已在使用者本機通過，包含 broker／Redis pause→unpause 案例；以上 Compose 手動演練與負載環境的恢復時間仍未量測。

## 現階段限制

- 單 broker、單 Redis、單 PostgreSQL；volume 有持久化但不等於備份或多機容錯。
- API 沒有登入、租戶隔離或可信 userId；查詢／付款端點也沒有 ownership 授權。admin 用本機 token，不能直接公開上線。
- 沒有真實付款 provider、webhook 簽章或外部退款；本版只示範狀態競爭。
- 所有成功 reservation 仍寫 DB；DB idempotency lookup 也有成本。Kafka 尖峰平滑針對建單；Redis 不是保證達到高吞吐的魔法。
- outbox／reservation／DLQ 未做歸檔，查詢與維護成本會隨資料量成長；單次 publisher batch 20，需實測調整。
- schema.sql 可重複建表，但沒有 Flyway migration／多版本滾動部署策略。
- 預設 300 秒 TTL 含排隊時間；過期、失敗後同 key 回原終態，重新購買要新 key。
