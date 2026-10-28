INSERT INTO tickets (event_id, total_stock, remaining)
VALUES (1, 100, 100)
ON CONFLICT (event_id) DO NOTHING;
