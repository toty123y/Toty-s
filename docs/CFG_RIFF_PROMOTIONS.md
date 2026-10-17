# CFG Riff Rumble: Shopify promotions requirements and rollout

Status: Work in progress. Android app reads only server-verified offers. **No Shopify connection or live ad service is configured yet.** This specification is not an activation.

## Approved user requirements
- Only owner-approved Shopify promotions. Promotion headline, subtitle and CTA written manually in Shopify. Optional marketing image overrides product featured image.
- **Home and Results only**, not between quiz questions. Results ad ABOVE PLAY AGAIN.
- Offers rotate per game. 0 verified eligible offers -> no ads. 1 -> Results only. 2+ -> Home and Results featuring different products.
- Each ad has X; close one to hide all until next full app launch. No banners when offline.
- High/Normal/Low weights: 5/3/1.
- Detect country roughly by server IP, without GPS permission; player can override under Home > Promotion Country.
- Shopify Markets supplies localized actual price/currency. For 2+1 display total for 3 units after verified discount, excluding shipping/tax. Never compute discounts from stale displayed prices.
- Only countries where deal is eligible; Shopify checkout shipping address makes final eligibility determination.
- Out-of-stock, insufficient quantities, unverified price or coupon, expired deal, Shopify outage -> hide ad. Product returns -> ad may return.
- Promo CTA opens website in external browser. B2G1F link must preserve existing cart, add three units, validate price, and ask customer which discount to retain on collision.
- Track impressions, clicks and attributable purchases (not all orders will be attributable). Compare performance per 1,000 impressions.
- Five-minute validity monitoring. A single immediate email to ariel@cablefreeguitar.com per newly detected incident, no recovery mail, new incident after recovery sends again. Planned expiry not treated as failure.
- Weekly Monday 09:00 Asia/Jerusalem email: last Monday-Sunday summary, CTR, orders, revenue, top campaigns and evidence-based recommendations. **Recommendations only**. No automatic changes to creative, discounts or priority.
- Additional approved improvements: Dark/Light ad preview prior to publishing; one emergency shutdown switch; private test mode for owner; standardized per-1,000-impressions reporting.
- **Explicitly NOT approved**: feature 5, special duplicate-click prevention. Do not add as user-facing scope without renewed approval.

## Integration architecture
1. Shopify Metaobject type `cfg_riff_promotion` represents each candidate offer; it has an explicit `approved` boolean, start/end times, copy, optional marketing image, priority, product and discount reference and allowed markets.
2. Shopify secure server checks actual product purchasability and at least 3 stock units for B2G1F, checks discount rules and calculates country-context cart total. This runs every five minutes and also when generating a checkout link. Do not put Admin API access tokens in the app.
3. Backend returns `enabled:false` on global kill, errors, unknown eligibility or no verified data; client fails closed. Draft offers only available via authenticated short-lived private preview, NEVER the public feed.
4. Android app first loads a public config from repository (currently OFF) and then, if enabled, fetches the approved HTTPS API endpoint. This allows changing backend location or activating remote service without an APK rebuild.
5. Events are sent only for sufficiently visible ad impressions and actual CTA clicks to a service-owned URL. Shopify/GA4 attribution via UTM links and sales events requires later implementation and privacy review. GA4 alone does not confirm all sales were caused by an ad.

## Network contract: GET /api/promotions/v1?country=AUTO|US
Response on success:
```json
{
  "enabled": true,
  "country": "US",
  "metricsUrl": "https://YOUR-OWNED-WORKER.workers.dev/api/promotions/events",
  "offers": [{
    "promotionId": "deadon-b2g1f",
    "productId": "shopify-product-id",
    "approved": true,
    "verified": true,
    "inStock": true,
    "country": "US",
    "priority": "Normal",
    "headline": "Small tuner. Big deal.",
    "subtitle": "Buy 2, get 1 free",
    "cta": "GRAB THE DEAL",
    "formattedPrice": "$59.98 for 3",
    "code": "DOB2G1F",
    "imageUrl": "https://cdn.shopify.com/...",
    "clickUrl": "https://www.cablefreeguitar.com/...",
    "verifiedUntilMs": 1792189200000
  }]
}
```
The timestamp is **an illustrative placeholder**. Set verifiedUntilMs dynamically based on Shopify verification, max 15 minutes after current server time. Never reuse sample prices or links. Client refuses HTTP or non-CFG purchase links, and refuses unverifiable records.

## Network contract: POST /api/promotions/events
Receive `{"type":"impression|click","promotionId":"...","placement":"home|results","country":"US"}`. Backend should verify event input, rate limit abuse and aggregate without collecting player identities.

## Phases and blockers
- Phase 1: Native Android placements, dismissal, weighted selection, country override, remote feature flag and tracking hooks. Build and QA in separate GitHub branch.
- Phase 2: Shopify connection, Metaobject creation, Shopify Markets/discount/cart validation, cloud API and sync. Requires owner-authorized Shopify access plus deployment ownership.
- Phase 3: QA preview and safe test mode, monitoring/emails, analytics and Monday digest. Nothing scheduled until confirmed ready.
- Phase 4: real device tests then approved public rollout.

Existing game/quiz/sounds must remain intact.