# Seed Data

Dummy datasets for exercising every feature of the platform against realistic
(if synthetic) data, instead of starting from an empty city.

## Contents

- `generate_seed_data.py` — regenerates the two JSON files below (uses [Faker](https://faker.readthedocs.io/); `pip install faker`)
- `traffic_sensor_readings.json` — 600 sensor readings across 4 zones × 5 sensor types × 2 sensors each, with the last 2 readings per sensor deliberately spiked to trigger the z-score anomaly detector
- `citizen_complaints.json` — 52 complaints across the same 4 zones, including:
  - varied categories (pothole, garbage, streetlight, water leakage, encroachment, noise)
  - a **duplicate cluster per zone** (same pothole worded 3 different ways) to exercise duplicate detection
  - **Hindi and Kannada complaints** to exercise `LanguageSupportService`'s detect-and-translate path
  - a **high-urgency "danger"-worded complaint** per zone to exercise urgency scoring
- `load_seed_data.sh` — POSTs both files into a running instance via curl (requires `jq`)

## Quick start

```bash
# 1. Bring the platform up
cd ..
docker compose up --build -d

# 2. Load the seed data (from this directory)
cd seed-data
./load_seed_data.sh
# or, with a non-default API key:
API_KEY=my-secret ./load_seed_data.sh
```

## Regenerating with different volumes

Edit the `generate_sensor_readings()` / `generate_complaints()` call arguments
at the bottom of `generate_seed_data.py` (e.g. `readings_per_sensor`,
`complaints_per_zone`), then:

```bash
pip install faker
python3 generate_seed_data.py
```

The script is seeded (`random.seed(42)`) so default output is reproducible.

## Testing the SLA-escalation feature

`SlaEscalationService` only fires on complaints old enough to have breached
their category's SLA (e.g. garbage: 24h, pothole: 120h) — freshly-loaded seed
data won't trigger it by design (it's *supposed* to represent "just filed").
To actually see an escalation draft without waiting days, backdate a few
complaints directly in MongoDB after loading:

```bash
docker exec -it urban_mongo mongosh -u urban_user -p urban_pass --authenticationDatabase admin urban_complaints --eval '
  db.citizen_complaints.updateMany(
    { category: "GARBAGE" },
    { $set: { createdAt: new Date(Date.now() - 30*60*60*1000) } } // 30h ago > 24h SLA
  )
'
```

Then either wait for the next scheduled sweep (`sla.check-cron`, default 8 AM
daily) or call the on-demand endpoint:

```bash
curl -H "X-API-Key: change-me-in-prod" http://localhost:8083/api/ai/sla-escalations
```

## Testing multi-zone comparison / city briefing

Once both datasets are loaded and a few Kafka consumer cycles have passed
(indexing + async classification), try:

```bash
curl -X POST http://localhost:8083/api/ai/compare-zones \
  -H "Content-Type: application/json" -H "X-API-Key: change-me-in-prod" \
  -d '{"zones":["Whitefield","Koramangala","Connaught Place","Andheri"],"question":"Which zone has the most urgent issues right now and why?"}'

curl -H "X-API-Key: change-me-in-prod" "http://localhost:8083/api/ai/city-briefing?refresh=true"
```
