# High-Concurrency Ticketing System

Gamma: https://gamma.app/docs/High-Concurrency-Ticketing-System-xi1ylkvvzj07pxg

## 1

100 張票，如何承受 10 萬次搶票？

HIGH-CONCURRENCY TICKETING SYSTEM / V1 → V8

從超賣實驗，到可驗證的可靠交易流程

Java 21 · Spring Boot · PostgreSQL · Redis · Kafka

100,000 次測試迭代｜1,000 VUs｜100 筆付款完成

工程實作與實測報告 · 2026.10.30

來自 vv

少於 1 分鐘前編輯過



---

## 2

01 / 先重現問題，才知道要修什麼
100 張票，曾經回報 1,000 次成功。

V1 故意保留 read → check → write 的競爭窗口；多個請求讀到相同庫存，再各自寫回舊值。

請求 A

	

請求 B




讀取 remaining = 1

	

讀取 remaining = 1




檢查有票 → 寫入 0

	

檢查有票 → 寫入 0




回報成功

	

也回報成功

⋮

同一張票被承諾兩次。 @Transactional 不能自動消除這類 lost update；延遲只讓問題更容易重現。

V1 實驗：初始 100｜成功回應 1,000｜最終剩餘 46。此端點僅供教學，須明確啟用。



---

## 3

02 / 八個階段，一條可靠交易路徑

階段

	

實作重點

	

解決的問題




V1 → V2

	

JPA 競爭 → 條件式 UPDATE

	

原子扣減庫存




V3 → V4

	

Redis + Lua → Outbox + Kafka

	

入口篩選、非同步訂單




V5 → V6

	

付款與逾時 → 冪等、重試、DLQ

	

重複與故障下維持資料規則




V7 → V8

	

k6 → Compose、API、完整文件

	

用可重現結果交付系統

⋮
POSTGRESQL 守住庫存與交易事實。

Redis 提供入口提示；Kafka 處理非同步工作，兩者都不能繞過資料庫的庫存防線。






---

## 4

03 / 最終架構：先持久化，再非同步處理
K6 / CLIENT → SPRING BOOT API → REDIS + LUA

驗證請求與 Idempotency-Key；Redis 篩選可能有票的流量。

POSTGRESQL：一個本地交易

條件式扣庫存 ＋ Reservation ＋ Outbox 同時提交 → 回應 202 與 reservationId

OUTBOX PUBLISHER → KAFKA → CONSUMER → ORDER

Publisher 收到 Kafka ACK 才標記已發送；Consumer 依 reservationId 冪等建立待付款訂單。

資料防線：原子 UPDATE 防超賣；唯一約束防重複；Outbox 避免資料已提交、事件卻遺失。

Docker Compose：API · PostgreSQL 16 · Redis 7.4 · Kafka 3.9.1



---

## 5

04 / 同一個請求，只產生一份交易結果

步驟

	

系統行為




① POST reserve

	

帶入 Idempotency-Key；重送同一 key 取回相同 reservationId




② 接受預約

	

UPDATE tickets … WHERE remaining > 0；扣庫存與寫入 Outbox 同交易




③ 202 Accepted

	

代表預約已持久化；訂單由 Kafka Consumer 後續建立




④ 查詢與付款

	

等待 PENDING_PAYMENT，再呼叫模擬付款 API

⋮
AT-LEAST-ONCE DELIVERY ＋ IDEMPOTENT PROCESSING

事件可能重送；orders 主鍵與 reservation 外鍵約束，讓同一預約不會建立多筆訂單。



---

## 6

05 / 付款與逾時，必須共用同一套規則
QUEUED → PENDING_PAYMENT → PAID

失敗或逾時會進入 FAILED / EXPIRED；可釋放的庫存只釋放一次。

競爭情境

	

保護方式




付款與逾時同時發生

	

資料庫列鎖與狀態檢查，交易內判定結果




重複付款通知

	

PAID 為終態；重送成功或稍後失敗都不能改回去




重複補償

	

條件式狀態轉移，避免庫存重複加回

⋮

逾時依資料庫 clock_timestamp() 判定；付款為模擬流程，尚未串接金流與退款。



---

## 7

06 / 故障不是跳過，而是留下可恢復的路徑

故障位置

	

恢復路徑




交易完成後 Kafka 暫時不可用

	

Outbox 保留待送事件，Publisher 重試




事件已送達但尚未標記

	

允許重送；Consumer 冪等處理




Consumer 處理持續失敗

	

重試後進 DLT，持久化 DLQ 紀錄，支援管理端重播




DLT 紀錄暫時無法寫入

	

專用錯誤處理器持續重試，避免 DLT 自我循環




Redis 與 DB 庫存提示不一致

	

DB 為準；Audit / Reconcile 協助校正

⋮

驗證邊界：具備恢復機制與整合測試；尚未完成高負載下的長時間故障注入與高可用驗證。



---

## 8

07 / 驗證分兩層：交易規則與完整旅程
17 項整合測試 ＋ 8 項核心測試通過

整合測試涵蓋最終流程與 V1 實驗；核心測試驗證重要分支。Maven verify 成功，沒有跳過整合案例。

測試層

	

驗證內容




整合與核心測試

	

庫存、冪等、付款終態、補償與故障恢復規則




k6 Admission

	

預約接受量、預期售罄、錯誤率與資料一致性




k6 Journey

	

預約 → Kafka 建單 → 付款 → 重播 → 最終 Audit

⋮

測試來源：專案驗證紀錄、JSON 摘要及原始壓測報告（2026.10.30）。



---

## 9

08 / 10 萬次實測：先看正確性，再看速度

指標

	

Admission

	

完整 Journey




迭代數 / VUs

	

100,000 / 1,000

	

100,000 / 1,000




接受預約 / 付款完成

	

100 / —

	

100 / 100




超賣 / 重複訂單 / 庫存差異

	

0 / 0 / 0

	

0 / 0 / 0




P95 / P99

	

857 ms / 1,190 ms

	

12.644 s / 12.873 s




執行時間

	

35.73 s

	

29.17 s

⋮

Admission 延遲為入口請求；Journey 延遲僅計 100 筆成功付款旅程。兩次使用不同活動，各有 100 張票。

解讀：100,000 為 shared iterations，並非同時連線或每秒請求；大多數請求為預期售罄 409。單機、短時間、closed-loop 測試。



---

## 10

09 / 可交付、可重現，也清楚保留限制
已交付

Docker Compose 一鍵啟動｜V1–V8 程式｜OpenAPI｜README｜逐行 HTML 導讀｜k6 腳本與實測報告

建議展示路線

① 啟動服務 → ② 示範 V1 競爭 → ③ 執行最終預約與付款 → ④ 檢查 Audit → ⑤ 開啟 10 萬次報告

下一步

登入與訂單權限、正式金流、持續負載與故障注入、多節點高可用、容量與部署成本評估。

目前結論：已在實測案例中維持不超賣、不重複建單與庫存一致；尚不宣稱具備生產環境 SLA 或 10 萬 RPS 能力。

GitHub：github.com/Yneq/high-concurrency-ticketing-system · 本次本地版本 e655575

