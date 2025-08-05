#!/usr/bin/env python3
import osmium
import os

class OSMHandler(osmium.SimpleHandler):
    def __init__(self, writer):
        osmium.SimpleHandler.__init__(self)
        self.writer = writer

    def node(self, n):
        self.writer.add_node(n)

    def way(self, w):
        self.writer.add_way(w)

    def relation(self, r):
        self.writer.add_relation(r)

def convert_pbf_to_osm_xml(input_pbf_path, output_osm_path):
    """
    Konwertuje plik .osm.pbf do formatu .osm (XML) za pomocą PyOsmium.

    Args:
        input_pbf_path (str): Ścieżka do wejściowego pliku .osm.pbf.
        output_osm_path (str): Ścieżka do wyjściowego pliku .osm (XML).
    """
    try:
        # Sprawdzamy czy plik wyjściowy już istnieje i go usuwamy
        if os.path.exists(output_osm_path):
            print(f"Usuwam istniejący plik: {output_osm_path}")
            os.remove(output_osm_path)

        # Tworzymy Writer do zapisu danych OSM w formacie XML
        writer = osmium.SimpleWriter(output_osm_path)

        # Tworzymy handler, który będzie przenosił wszystkie obiekty do writera
        handler = OSMHandler(writer)

        # Przetwarzamy plik PBF
        handler.apply_file(input_pbf_path)

        # Zamykamy writera
        writer.close()
        print(f"Konwersja zakończona sukcesem: '{input_pbf_path}' -> '{output_osm_path}'")

    except FileNotFoundError:
        print(f"Błąd: Nie znaleziono pliku wejściowego pod ścieżką: '{input_pbf_path}'")
    except Exception as e:
        print(f"Wystąpił nieoczekiwany błąd: {e}")

# Przykład użycia:
if __name__ == "__main__":
    input_file = 'mazowieckie-latest.osm.pbf'
    output_file = 'mazowieckie-latest.osm'
    convert_pbf_to_osm_xml(input_file, output_file)