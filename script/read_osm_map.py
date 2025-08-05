#!/usr/bin/env python3
"""
Skrypt do analizy danych OSM w formacie XML i znajdowania połączonych elementów ulic
"""

import xml.etree.ElementTree as ET
from collections import defaultdict
import sys
import argparse

def parse_osm_file(file_path):
    """Parsuje plik OSM XML i zwraca słowniki z węzłami i drogami"""
    print(f"Parsowanie pliku OSM: {file_path}")
    
    # Słowniki do przechowywania danych
    nodes = {}  # id_węzła -> (lat, lon)
    ways = {}   # id_drogi -> lista id_węzłów
    way_tags = {}  # id_drogi -> słownik tagów
    
    # Parsowanie pliku XML
    tree = ET.parse(file_path)
    root = tree.getroot()
    
    # Zbieranie węzłów
    for node in root.findall('./node'):
        node_id = node.get('id')
        lat = float(node.get('lat'))
        lon = float(node.get('lon'))
        nodes[node_id] = (lat, lon)
    
    # Zbieranie dróg
    for way in root.findall('./way'):
        way_id = way.get('id')
        nd_refs = []
        tags = {}
        
        # Zbieranie referencji do węzłów
        for nd in way.findall('./nd'):
            nd_refs.append(nd.get('ref'))
        
        # Zbieranie tagów
        for tag in way.findall('./tag'):
            k = tag.get('k')
            v = tag.get('v')
            tags[k] = v
        
        ways[way_id] = nd_refs
        way_tags[way_id] = tags
    
    return nodes, ways, way_tags

def build_node_to_ways_index(ways):
    """Buduje indeks węzłów do dróg (które drogi zawierają dany węzeł)"""
    node_to_ways = defaultdict(list)
    
    for way_id, node_refs in ways.items():
        for node_ref in node_refs:
            node_to_ways[node_ref].append(way_id)
    
    return node_to_ways

def find_ways_by_name(street_name, way_tags):
    """Znajduje wszystkie drogi o podanej nazwie"""
    matching_ways = []
    
    for way_id, tags in way_tags.items():
        if tags.get('name', '').lower() == street_name.lower():
            matching_ways.append(way_id)
    
    return matching_ways

def get_way_nodes_with_coords(way_id, ways, nodes):
    """Zwraca listę węzłów drogi wraz z ich współrzędnymi"""
    result = []
    way_nodes = ways.get(way_id, [])
    
    for node_ref in way_nodes:
        if node_ref in nodes:
            lat, lon = nodes[node_ref]
            result.append({
                'node_id': node_ref,
                'lat': lat,
                'lon': lon
            })
    
    return result

def find_connected_ways(way_id, ways, node_to_ways):
    """Znajduje drogi połączone z daną drogą"""
    connected_ways = set()
    
    # Pobierz węzły dla danej drogi
    way_nodes = ways.get(way_id, [])
    
    # Sprawdź każdy węzeł w drodze
    for node_ref in way_nodes:
        # Znajdź wszystkie drogi, które zawierają ten węzeł
        for connected_way_id in node_to_ways.get(node_ref, []):
            # Nie dodawaj samej siebie
            if connected_way_id != way_id:
                connected_ways.add(connected_way_id)
    
    return connected_ways

def find_next_street_segments(way_id, ways, node_to_ways, way_tags):
    """
    Znajduje segmenty ulicy, które mogą być kontynuacją danej ulicy
    Sprawdza węzły końcowe i nazwy ulic
    """
    result = []
    
    # Pobierz węzły dla danej drogi
    way_nodes = ways.get(way_id, [])
    if not way_nodes:
        return result
    
    # Pobierz nazwę ulicy
    street_name = way_tags.get(way_id, {}).get('name', '')
    
    # Sprawdź węzły końcowe (pierwszy i ostatni)
    end_nodes = [way_nodes[0], way_nodes[-1]]
    
    for node_ref in end_nodes:
        # Znajdź wszystkie drogi, które zawierają ten węzeł końcowy
        for connected_way_id in node_to_ways.get(node_ref, []):
            # Nie dodawaj samej siebie
            if connected_way_id != way_id:
                # Sprawdź, czy nazwa ulicy jest taka sama (jeśli istnieje)
                connected_name = way_tags.get(connected_way_id, {}).get('name', '')
                
                # Jeśli nazwa jest taka sama lub brak nazwy, to prawdopodobnie kontynuacja
                if not street_name or not connected_name or street_name == connected_name:
                    result.append({
                        'way_id': connected_way_id,
                        'name': connected_name,
                        'shared_node': node_ref,
                        'is_same_name': street_name == connected_name
                    })
    
    return result

def find_intersecting_streets(way_id, ways, node_to_ways, way_tags):
    """Znajduje ulice przecinające daną ulicę (różne nazwy)"""
    result = []
    
    # Pobierz węzły dla danej drogi
    way_nodes = ways.get(way_id, [])
    if not way_nodes:
        return result
    
    # Pobierz nazwę ulicy
    street_name = way_tags.get(way_id, {}).get('name', '')
    
    # Sprawdź każdy węzeł w drodze (nie tylko końcowe)
    for node_ref in way_nodes:
        # Znajdź wszystkie drogi, które zawierają ten węzeł
        for connected_way_id in node_to_ways.get(node_ref, []):
            # Nie dodawaj samej siebie
            if connected_way_id != way_id:
                # Sprawdź, czy nazwa ulicy jest inna
                connected_name = way_tags.get(connected_way_id, {}).get('name', '')
                
                # Jeśli nazwy są różne i obie istnieją, to prawdopodobnie przecięcie
                if street_name and connected_name and street_name != connected_name:
                    result.append({
                        'way_id': connected_way_id,
                        'name': connected_name,
                        'shared_node': node_ref
                    })
    
    return result

def analyze_street_by_name(street_name, osm_file):
    """Analizuje ulicę o podanej nazwie i zwraca informacje o niej"""
    # Parsuj plik OSM
    nodes, ways, way_tags = parse_osm_file(osm_file)
    
    # Buduj indeks węzłów do dróg
    node_to_ways = build_node_to_ways_index(ways)
    
    # Znajdź wszystkie drogi o podanej nazwie
    matching_ways = find_ways_by_name(street_name, way_tags)
    
    if not matching_ways:
        print(f"\nNie znaleziono ulic o nazwie '{street_name}'")
        return
    
    print(f"\nZnaleziono {len(matching_ways)} segmentów ulicy '{street_name}'")
    
    # Analizuj każdy segment ulicy
    for i, way_id in enumerate(matching_ways):
        print(f"\n--- Segment {i+1}/{len(matching_ways)} (ID: {way_id}) ---")
        
        # Informacje o segmencie
        highway_type = way_tags[way_id].get('highway', 'brak')
        surface = way_tags[way_id].get('surface', 'brak')
        way_nodes = ways.get(way_id, [])
        
        print(f"Typ drogi: {highway_type}")
        print(f"Nawierzchnia: {surface}")
        print(f"Liczba węzłów: {len(way_nodes)}")
        
        # Wyświetl węzły z współrzędnymi
        nodes_with_coords = get_way_nodes_with_coords(way_id, ways, nodes)
        print("\nWęzły drogi (w kolejności):\n")
        for j, node_data in enumerate(nodes_with_coords):
            print(f"  {j+1}. ID: {node_data['node_id']}, Współrzędne: ({node_data['lat']}, {node_data['lon']})")
        
        # Znajdź kontynuacje ulicy
        next_segments = find_next_street_segments(way_id, ways, node_to_ways, way_tags)
        if next_segments:
            print("\nKontynuacje ulicy:")
            for segment in next_segments:
                same_name_info = "ta sama nazwa" if segment['is_same_name'] else "inna nazwa"
                print(f"  - Droga {segment['way_id']}: {segment['name']} (węzeł wspólny: {segment['shared_node']}, {same_name_info})")
        
        # Znajdź przecinające się ulice
        intersections = find_intersecting_streets(way_id, ways, node_to_ways, way_tags)
        if intersections:
            print("\nUlice przecinające:")
            for intersection in intersections:
                print(f"  - Droga {intersection['way_id']}: {intersection['name']} (węzeł wspólny: {intersection['shared_node']})")

def main():
    # Konfiguracja parsera argumentów
    parser = argparse.ArgumentParser(description='Analizuje ulice w pliku OSM XML.')
    parser.add_argument('osm_file', help='Ścieżka do pliku OSM XML')
    parser.add_argument('street_name', help='Nazwa ulicy do wyszukania')
    
    # Parsowanie argumentów
    args = parser.parse_args()
    
    # Analizuj ulicę o podanej nazwie
    analyze_street_by_name(args.street_name, args.osm_file)

if __name__ == "__main__":
    main()
