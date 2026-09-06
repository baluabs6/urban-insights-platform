#!/usr/bin/env python3
"""
Generates dummy datasets for the Urban Insights Platform so a fresh
`docker compose up` can be populated with realistic-looking data immediately,
instead of starting from an empty city.

Outputs (into ./seed-data/):
  - traffic_sensor_readings.json   -> POST to traffic-service /api/traffic/ingest-sync
  - citizen_complaints.json        -> POST to complaint-service /api/complaints

Design choices, so the generated data actually exercises the features we built:
  - A handful of readings per sensor are deliberately spiked far outside that
    sensor's normal range, so traffic-service's z-score detector should flag
    them as anomalies (exercises anomaly-explanation, traffic.anomalies Kafka
    topic, index-on-write).
  - A cluster of near-identical complaint descriptions (same pothole, worded
    differently) is included per zone, to exercise duplicate detection.
  - Some complaints are given old timestamps embedded in the description
    context (createdAt is actually stamped by complaint-service on submit, so
    to exercise SLA breach detection you need to submit them and then either
    wait, or backdate directly in Mongo — see seed-data/README.md).
  - A few complaint descriptions are written in Hindi/Kannada to exercise
    LanguageSupportService's detect-and-translate path.
  - Zones match ai-insight-service's configured city.zones default:
    Whitefield, Koramangala, Connaught Place, Andheri.

Usage:
    pip install faker
    python3 generate_seed_data.py
"""
import json
import random
import uuid
from pathlib import Path

from faker import Faker

fake = Faker("en_IN")
random.seed(42)  # reproducible output

OUT_DIR = Path(__file__).parent
ZONES = ["Whitefield", "Koramangala", "Connaught Place", "Andheri"]

ZONE_COORDS = {
    "Whitefield": (12.9698, 77.7500),
    "Koramangala": (12.9352, 77.6245),
    "Connaught Place": (28.6315, 77.2167),
    "Andheri": (19.1197, 72.8468),
}

SENSOR_TYPES = {
    "AQI": {"unit": "AQI", "normal_range": (60, 180), "spike_range": (350, 500)},
    "TRAFFIC_FLOW": {"unit": "vehicles/5min", "normal_range": (40, 220), "spike_range": (500, 900)},
    "NOISE": {"unit": "dB", "normal_range": (45, 70), "spike_range": (95, 120)},
    "WATER_LEVEL": {"unit": "cm", "normal_range": (10, 40), "spike_range": (90, 150)},
    "PARKING": {"unit": "occupied_spots", "normal_range": (5, 60), "spike_range": (95, 100)},
}

COMPLAINT_TEMPLATES = {
    "POTHOLE": [
        "Large pothole near {landmark}, cars are swerving dangerously to avoid it.",
        "Deep pothole on the main road by {landmark}, caused a two-wheeler accident yesterday.",
        "Road has multiple potholes near {landmark}, badly needs repair before monsoon.",
    ],
    "GARBAGE": [
        "Garbage has not been collected near {landmark} for over a week, smell is unbearable.",
        "Overflowing garbage bin outside {landmark}, attracting stray animals.",
        "Illegal dumping of construction waste near {landmark}.",
    ],
    "STREETLIGHT": [
        "Streetlight outside {landmark} has been broken for 10 days, area is unsafe at night.",
        "Multiple streetlights not working on the stretch near {landmark}.",
    ],
    "WATER_LEAKAGE": [
        "Major water pipeline leakage near {landmark}, wasting a lot of water daily.",
        "Sewage water leaking onto the road near {landmark}, health hazard.",
    ],
    "ENCROACHMENT": [
        "Vendors have encroached on the footpath near {landmark}, pedestrians forced onto the road.",
    ],
    "NOISE": [
        "Construction near {landmark} runs loud machinery late at night, disturbing residents.",
    ],
}

# Same underlying issue, worded differently — for exercising duplicate detection.
DUPLICATE_CLUSTER_TEMPLATES = [
    "Big pothole right outside {landmark}, very dangerous for two-wheelers.",
    "There is a huge pothole near {landmark}, almost caused an accident.",
    "The pothole close to {landmark} has gotten much bigger after the rain.",
]

# A few complaints in Hindi/Kannada to exercise LanguageSupportService.
MULTILINGUAL_COMPLAINTS = [
    ("hi", "{landmark} ke paas sadak par bada gadha hai, gaadiyon ko nuksan ho raha hai."),
    ("hi", "{landmark} ke pass kai dinon se kachra nahi uthaya gaya hai, badboo aa rahi hai."),
    ("kn", "{landmark} ಬಳಿ ಬೀದಿ ದೀಪ ಕೆಟ್ಟಿದೆ, ರಾತ್ರಿ ತುಂಬಾ ಕತ್ತಲೆ ಇರುತ್ತದೆ."),
]

LANDMARKS = [
    "the main bus stop", "the community park entrance", "the metro station",
    "the government school", "the market junction", "the water tank",
    "the flyover underpass", "the residents' welfare office",
]


def jitter_coords(lat, lon):
    return round(lat + random.uniform(-0.01, 0.01), 6), round(lon + random.uniform(-0.01, 0.01), 6)


def generate_sensor_readings(readings_per_sensor=15, sensors_per_zone_type=2):
    readings = []
    for zone in ZONES:
        base_lat, base_lon = ZONE_COORDS[zone]
        for sensor_type, spec in SENSOR_TYPES.items():
            for sensor_idx in range(sensors_per_zone_type):
                sensor_id = f"{sensor_type}-{zone[:2].upper()}-{sensor_idx + 1:02d}"
                lat, lon = jitter_coords(base_lat, base_lon)

                # Emit a run of normal readings, then a couple of deliberate anomalies.
                for i in range(readings_per_sensor):
                    is_anomaly = i >= readings_per_sensor - 2  # last 2 readings per sensor are spikes
                    low, high = spec["spike_range"] if is_anomaly else spec["normal_range"]
                    value = round(random.uniform(low, high), 2)
                    readings.append({
                        "sensorId": sensor_id,
                        "sensorType": sensor_type,
                        "zone": zone,
                        "latitude": lat,
                        "longitude": lon,
                        "value": value,
                        "unit": spec["unit"],
                    })
    return readings


def generate_complaints(complaints_per_zone=8, duplicate_clusters_per_zone=1):
    complaints = []
    categories = list(COMPLAINT_TEMPLATES.keys())

    for zone in ZONES:
        base_lat, base_lon = ZONE_COORDS[zone]

        # Regular varied complaints across categories.
        for _ in range(complaints_per_zone):
            category = random.choice(categories)
            template = random.choice(COMPLAINT_TEMPLATES[category])
            landmark = random.choice(LANDMARKS)
            lat, lon = jitter_coords(base_lat, base_lon)
            complaints.append({
                "citizenId": f"CIT-{uuid.uuid4().hex[:8].upper()}",
                "category": category,
                "description": template.format(landmark=landmark),
                "zone": zone,
                "latitude": lat,
                "longitude": lon,
                "photoUrls": [],
            })

        # Duplicate cluster: same pothole, described 3 different ways.
        for _ in range(duplicate_clusters_per_zone):
            landmark = random.choice(LANDMARKS)
            lat, lon = jitter_coords(base_lat, base_lon)
            for template in DUPLICATE_CLUSTER_TEMPLATES:
                complaints.append({
                    "citizenId": f"CIT-{uuid.uuid4().hex[:8].upper()}",
                    "category": "POTHOLE",
                    "description": template.format(landmark=landmark),
                    "zone": zone,
                    "latitude": lat,
                    "longitude": lon,
                    "photoUrls": [],
                })

        # One multilingual complaint per zone (cycling through the examples).
        lang_code, template = random.choice(MULTILINGUAL_COMPLAINTS)
        landmark = random.choice(LANDMARKS)
        lat, lon = jitter_coords(base_lat, base_lon)
        complaints.append({
            "citizenId": f"CIT-{uuid.uuid4().hex[:8].upper()}",
            "category": "POTHOLE" if "gadha" in template or "ಬೀದಿ" not in template else "STREETLIGHT",
            "description": template.format(landmark=landmark),
            "zone": zone,
            "latitude": lat,
            "longitude": lon,
            "photoUrls": [],
        })

        # One high-urgency, danger-flagged complaint per zone (tests urgency heuristics).
        lat, lon = jitter_coords(base_lat, base_lon)
        complaints.append({
            "citizenId": f"CIT-{uuid.uuid4().hex[:8].upper()}",
            "category": "WATER_LEAKAGE",
            "description": f"Danger: sewage water leak near {random.choice(LANDMARKS)} has flooded "
                            f"the road, risk of accidents at night.",
            "zone": zone,
            "latitude": lat,
            "longitude": lon,
            "photoUrls": [],
        })

    random.shuffle(complaints)
    return complaints


def main():
    readings = generate_sensor_readings()
    complaints = generate_complaints()

    (OUT_DIR / "traffic_sensor_readings.json").write_text(json.dumps(readings, indent=2, ensure_ascii=False))
    (OUT_DIR / "citizen_complaints.json").write_text(json.dumps(complaints, indent=2, ensure_ascii=False))

    print(f"Generated {len(readings)} sensor readings -> traffic_sensor_readings.json")
    print(f"Generated {len(complaints)} citizen complaints -> citizen_complaints.json")
    anomaly_count = sum(
        1 for r in readings
        if r["value"] >= SENSOR_TYPES[r["sensorType"]]["spike_range"][0]
    )
    print(f"  ({anomaly_count} readings are deliberate spikes intended to trigger anomaly detection)")


if __name__ == "__main__":
    main()
