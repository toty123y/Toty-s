-- Private, non-PII event counters. Run with Cloudflare D1 after connecting own Cloudflare account.
CREATE TABLE IF NOT EXISTS promo_events (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 event_type TEXT NOT NULL CHECK(event_type IN ('impression','click')),
 promotion_id TEXT NOT NULL,
 placement TEXT NOT NULL CHECK(placement IN ('home','results')),
 country TEXT NOT NULL,
 recorded_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_promo_events_recorded ON promo_events(recorded_at);
CREATE INDEX IF NOT EXISTS idx_promo_events_campaign ON promo_events(promotion_id, event_type);
-- Populate sales ONLY from verified Shopify attribution. No conversion rows are invented.
CREATE TABLE IF NOT EXISTS promo_sales (
 shopify_order_id TEXT PRIMARY KEY,
 promotion_id TEXT NOT NULL,
 country TEXT NOT NULL,
 revenue_amount REAL NOT NULL CHECK(revenue_amount>=0),
 currency_code TEXT NOT NULL,
 ordered_at TEXT NOT NULL
);
-- Incident key is unique until resolved. Actual mail delivery happens server-side.
CREATE TABLE IF NOT EXISTS promo_incidents (
 campaign_id TEXT NOT NULL,
 reason TEXT NOT NULL,
 active INTEGER NOT NULL DEFAULT 1,
 first_seen TEXT NOT NULL DEFAULT (datetime('now')),
 last_seen TEXT NOT NULL DEFAULT (datetime('now')),
 PRIMARY KEY(campaign_id, reason)
);
-- Accurate normalised rates; NULL for no impressions. Sales require attribution, not assumptions.
-- SELECT campaign, views, clicks, orders, ROUND(clicks*100.0/NULLIF(views,0),2) AS ctr_pct,
-- ROUND(orders*1000.0/NULLIF(views,0),2) AS orders_per_1000_impressions FROM (...);
