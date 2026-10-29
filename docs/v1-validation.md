# 驗證紀錄

2026-10-28，Java 21.0.11、Maven 3.9.16、Docker Desktop、k6 0.57.0。

## 已完成

- `docker compose config --quiet`：通過。
- `mvn test`（前次 Docker 啟動後）：2 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS。
- 整合測試使用真實 PostgreSQL 16，涵蓋 GET、POST、100 張初始化、單次扣庫存、404、409，以及 20 個同時成功預約但庫存扣減小於 20 的 lost update。
- Docker 重啟後，完整多階段容器建置成功，app 與 db 已啟動。
- 本機 5432、8080 被佔用，新增可設定的 POSTGRES_PORT、APP_PORT；本次使用 5433、18080，沒有停止佔用原埠的其他服務。

## k6 實測

預設教學延遲 50ms，初始庫存 100 張；1000 VUs，各預約一次。

| 指標 | 實測 |
| --- | ---: |
| 預約迭代 | 1000 |
| 成功預約 | 1000 |
| 售罄回應 | 0 |
| 非預期回應 | 0 |
| HTTP 失敗 | 0 / 1002 |
| 最終剩餘庫存 | 46 |
| 實際庫存扣減 | 54 |
| HTTP 平均延遲（含前後 GET） | 2.92s |
| HTTP p95 延遲（含前後 GET） | 5.48s |

1000 次成功只扣掉 54 張，遺失 946 次庫存扣減；成功承諾超出原始 100 張共 900 張。庫存沒有變成負數，仍然明確重現 lost update 與超賣。此數據是本次機器與排程下的結果，後續執行不保證相同。

所有 k6 thresholds 通過，exit code 0；這表示**成功重現刻意保留的缺陷**。

## 本次重現指令

```bash
POSTGRES_PORT=5433 APP_PORT=18080 docker compose up --build -d db app
curl http://localhost:18080/api/tickets/1
docker compose exec -T db psql -U ticketing -d ticketing -c 'UPDATE tickets SET total_stock=100, remaining=100 WHERE event_id=1;'
docker compose run --rm k6 run --quiet /scripts/reserve.js
docker compose exec -T db psql -U ticketing -d ticketing -c 'SELECT event_id,total_stock,remaining FROM tickets;'
```

容器保留運行，資料庫保留壓測後狀態。再次壓測前請依 README 重設庫存。
