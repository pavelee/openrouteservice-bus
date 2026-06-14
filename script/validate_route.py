#!/usr/bin/env python3
"""
Harness walidacyjny trasy autobusowej — replikuje żądanie produkcyjne ORS dla
danego route_id (przystanki z bazy + logika stopLat/stopLon + bearingi +
custom_model) i wypisuje kroki trasy oraz dystans.

Użycie:
    python3 validate_route.py <ROUTE_ID> [ORS_BASE]
    python3 validate_route.py 481380

WAŻNE: BUS_CUSTOM_MODEL poniżej MUSI być zsynchronizowany z
web/app/_service/directions/orsBusCustomModel.ts. Reguła `lanes == 1` działa
DOPIERO po rebuildzie grafu z EV 'lanes' (ORSGraphHopperConfig dokłada "lanes"
do graph.encoded_values). Bez tego ORS zwróci błąd 2018 (identifier lanes invalid).
"""
import json, math, subprocess, sys, urllib.request

ORS_BASE = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080/ors"
ROUTE_ID = sys.argv[1] if len(sys.argv) > 1 else "481380"

# --- custom_model: lustro orsBusCustomModel.ts (BUS_CUSTOM_MODEL) ---
BUS_CUSTOM_MODEL = {
    "distance_influence": 1000,
    "speed": [{"if": "road_class == LIVING_STREET", "multiply_by": 0.5}],
    "priority": [
        {"if": "bus$preferred == true", "multiply_by": 1.0},
        {"else": "", "multiply_by": 0.5},
        {"if": "road_class == TERTIARY", "multiply_by": 0.857},
        {"if": "road_class == TERTIARY && lanes == 1", "multiply_by": 0.4},
        {"if": "road_class == RESIDENTIAL || road_class == UNCLASSIFIED", "multiply_by": 0.714},
        {"if": "road_class == RESIDENTIAL && max_speed > 90", "multiply_by": 0.5},
        {"if": "road_class == SERVICE && bus$preferred == false", "multiply_by": 0.05},
        {"if": "road_class == TRACK", "multiply_by": 0.286},
        {"if": "road_class == LIVING_STREET", "multiply_by": 0.143},
    ],
}


def fetch_stops(route_id):
    sql = (
        'SELECT brs.order_number, bs."teamName", bs.lat, bs.lon, bs."stopLat", bs."stopLon" '
        "FROM bus_route_stop brs JOIN bus_stop bs ON bs.team=brs.bus_stop_team "
        "AND bs.number=brs.bus_stop_number "
        f"WHERE brs.bus_route_id={int(route_id)} ORDER BY brs.order_number;"
    )
    out = subprocess.run(
        ["docker", "exec", "-e", "PGPASSWORD=postgres", "traska-db", "psql", "-h",
         "localhost", "-U", "postgres", "-d", "mydb", "-At", "-F", "|", "-c", sql],
        capture_output=True, text=True, check=True,
    ).stdout.strip().splitlines()
    stops = []
    for line in out:
        ordn, name, lat, lon, slat, slon = (line.split("|") + [None] * 6)[:6]
        stops.append((int(ordn), name, float(lat), float(lon),
                      float(slat) if slat else None, float(slon) if slon else None))
    return stops


def haversine(lat1, lon1, lat2, lon2):
    R = 6371.0
    dLat, dLon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dLat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dLon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def bearing(f, t):
    lat1, lat2, dLng = math.radians(f[1]), math.radians(t[1]), math.radians(t[0] - f[0])
    y = math.sin(dLng) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dLng)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def circmean(b1, b2):
    avg = math.atan2(math.sin(math.radians(b1)) + math.sin(math.radians(b2)),
                     math.cos(math.radians(b1)) + math.cos(math.radians(b2)))
    return (math.degrees(avg) + 360) % 360


def build_bearings(coords):
    BASE, MAXT = 30, 90
    out = []
    for i, c in enumerate(coords):
        if len(coords) < 2:
            out.append([0, 360]); continue
        if i == 0:
            out.append([round(bearing(c, coords[i + 1])) % 360, BASE]); continue
        if i == len(coords) - 1:
            out.append([round(bearing(coords[i - 1], c)) % 360, BASE]); continue
        inB, outB = bearing(coords[i - 1], c), bearing(c, coords[i + 1])
        ta = abs(outB - inB); ta = 360 - ta if ta > 180 else ta
        if ta > MAXT:
            out.append([]); continue
        out.append([round(circmean(inB, outB)) % 360, min(BASE + round(ta / 2), 180)])
    return out


def main():
    stops = fetch_stops(ROUTE_ID)
    coords = []
    for _, _, lat, lon, slat, slon in stops:
        if slat is not None and slon is not None and haversine(slat, slon, lat, lon) <= 1:
            coords.append([slon, slat])
        else:
            coords.append([lon, lat])
    body = {
        "coordinates": coords, "bearings": build_bearings(coords), "language": "pl",
        "continue_straight": "true", "preference": "shortest", "custom_model": BUS_CUSTOM_MODEL,
    }
    req = urllib.request.Request(f"{ORS_BASE}/v2/directions/driving-bus/geojson",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        d = json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        print("BŁĄD ORS:", e.read().decode()); sys.exit(1)
    f = d["features"][0]
    print(f"route {ROUTE_ID}: dystans={f['properties']['summary']['distance']:.1f} m, "
          f"czas={f['properties']['summary']['duration']:.0f} s")
    print("--- kroki (nazwa | instrukcja) ---")
    for seg in f["properties"]["segments"]:
        for s in seg["steps"]:
            if s.get("name", "-") != "-":
                print(f"  {s.get('distance'):6.0f} m  {s.get('name'):30s} | {s.get('instruction')}")


if __name__ == "__main__":
    main()
