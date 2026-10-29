-- Run only for a NEW event; never reset live reservations in place.
-- Example: docker compose exec -T db psql -U ticketing -d ticketing -f - < scripts/new-event.sql
INSERT INTO tickets(event_id,total_stock,remaining) VALUES(1001,100,100);
