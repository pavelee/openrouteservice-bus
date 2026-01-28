#!/usr/bin/env python3
"""
Script to process large OSM files and modify private residential roads to tertiary highways.
This script uses lxml for efficient XML parsing with low memory usage and ensures valid XML output.
"""

import sys
import os
import time
from lxml import etree

def process_osm_file(input_file, output_file, skip_relations=None):
    """Process OSM file using streaming parsing to ensure valid XML output."""
    
    if skip_relations is None:
        skip_relations = []
    
    # Check if input file exists
    if not os.path.isfile(input_file):
        print(f"Error: Input file '{input_file}' does not exist.")
        return False
    
    # Check if output file already exists
    if os.path.isfile(output_file):
        response = input(f"Warning: Output file '{output_file}' already exists. Do you want to overwrite it? (y/n): ")
        if response.lower() != 'y':
            print("Operation cancelled.")
            return False
    
    # Get file size for progress reporting
    file_size = os.path.getsize(input_file)
    print(f"Processing OSM file: {input_file}")
    print(f"Output will be saved to: {output_file}")
    print(f"Input file size: {file_size / (1024*1024):.2f} MB")
    
    # Count ways that will be modified
    modified_ways = 0
    total_elements = 0
    start_time = time.time()
    
    try:
        # Create a custom handler to process the XML
        class OSMHandler:
            def __init__(self, output_file):
                self.output_file = output_file
                self.current_way = None
                self.current_relation = None
                self.in_way = False
                self.in_relation = False
                self.is_private = False
                self.is_residential = False
                self.modified_count = 0
                self.skipped_relations = 0
                self.depth = 0
                
                # 1963216 - zakręt w lewo w piękną (obok sejmu) np. 131
                # Lista relacji do pominięcia - domyślnie 1963216 + dodatkowe z parametru
                self.skip_relations = ['1963216'] + skip_relations
                
                # Open output file
                self.out = open(output_file, 'wb')
                # Write XML declaration
                self.out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n')
            
            def start_element(self, name, attrs):
                self.depth += 1
                
                # Handle root element
                if self.depth == 1:
                    self.out.write(f'<{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                    return
                
                # Handle way elements
                if name == 'way':
                    self.in_way = True
                    self.current_way = attrs.get('id', '')
                    self.is_private = False
                    self.is_residential = False
                    
                    # Write way opening tag with attributes
                    self.out.write(f'  <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                
                # Handle relation elements
                elif name == 'relation':
                    self.in_relation = True
                    self.current_relation = attrs.get('id', '')
                    
                    # Check if this relation should be skipped
                    if self.current_relation in self.skip_relations:
                        print(f"Skipping relation ID: {self.current_relation}")
                        self.skipped_relations += 1
                        return  # Skip this relation entirely
                    
                    # Write relation opening tag with attributes
                    self.out.write(f'  <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                
                # Handle tags within ways
                elif self.in_way and name == 'tag':
                    k = attrs.get('k', '')
                    v = attrs.get('v', '')

                    # reczne ubicie sciezki
                    # 171028660 - czarnomorksa zakret
                    # 206528330 - rezedowa, 402
                    # 114895531 - Wyszczółki 331
                    if self.current_way in ['506254774', '491365793', '206528330', '114895531'] and k == 'highway':
                        self.out.write(f'    <{name} k="highway" v="construction"/>\n'.encode('utf-8'))
                        return 

                    # cholera jasna problem z remontami!
                    # Aleja Niepodległości! 
                    # if self.current_way in ['331762058', '952058010', '116931784', '187536173'] and k == 'oneway':
                    #     return
                    #     self.out.write(f'    <{name} k="highway" v="construction"/>\n'.encode('utf-8'))

                    # zablokowanie Zagłoby aby poprawić cholerny 187
                    if self.current_way in ['33276900']:
                        return
                    
                    # zablokowanie wiejskiej - 131
                    if self.current_way in ['888011097', '174143991', '386852929']:
                        return
                    
                    # zablokowanie Generała Michała Tokarzewskiego-Karaszewicza aby autobus 128 jechał Królewską
                    if self.current_way in ['860371908']:
                        return

                    # NOWY ŚWIAT KURCZAKI
                    # if self.current_way in ['24384574', '306458016', '137020852', '137020852', '1111601198', '882352736'] and k == 'access' and v == 'private':
                    #     self.is_private = True
                    #     return

                    # Skip access=private tags
                    if k == 'access' and v == 'private':
                        self.is_private = True
                        return  # Skip writing this tag

                    if k == 'access' and v == 'no':
                        self.is_private = True
                        return  # Skip writing this tag
                    
                    # # Change highway=residential to highway=tertiary
                    # if k == 'highway' and v == 'residential':
                    #     self.is_residential = True
                    #     self.out.write(f'    <{name} k="highway" v="tertiary"/>\n'.encode('utf-8'))
                    #     return

                    # change highway=unclassified to highway=tertiary
                    # if k == 'highway' and v == 'unclassified':
                    #     self.out.write(f'    <{name} k="highway" v="residential"/>\n'.encode('utf-8'))
                    #     return

                    # change highway=construction to highway=secondary
                    if k == 'highway' and v == 'construction':
                        self.out.write(f'    <{name} k="highway" v="secondary"/>\n'.encode('utf-8'))
                        return   
                    if k == 'construction':
                        return  # Skip writing this tag  
                    
                    # Write other tags normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle tags within relations
                elif self.in_relation and name == 'tag':
                    # Skip all tags for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    # Write relation tags normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle members within relations
                elif self.in_relation and name == 'member':
                    # Skip all members for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    # Write relation members normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle nd references within ways
                elif self.in_way and name == 'nd':
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle all other elements
                else:
                    indent = '  ' * (self.depth - 1)
                    self.out.write(f'{indent}<{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
            
            def end_element(self, name):
                # Handle way closing
                if name == 'way' and self.in_way:
                    self.in_way = False
                    self.out.write(b'  </way>\n')
                    
                    # Count modified ways
                    if self.is_private or self.is_residential:
                        self.modified_count += 1
                
                # Handle relation closing
                elif name == 'relation' and self.in_relation:
                    self.in_relation = False
                    
                    # Skip closing tag for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    self.out.write(b'  </relation>\n')
                
                # Handle root element
                elif self.depth == 1:
                    self.out.write(f'</{name}>'.encode('utf-8'))
                
                # Handle other elements (not way, nd, tag, member)
                elif not (self.in_way and (name == 'nd' or name == 'tag')) and not (self.in_relation and (name == 'member' or name == 'tag')):
                    indent = '  ' * (self.depth - 1)
                    self.out.write(f'{indent}</{name}>\n'.encode('utf-8'))
                
                self.depth -= 1
            
            def handle_text(self, content):
                # Handle text content (rare in OSM files)
                if content and content.strip():
                    indent = '  ' * self.depth
                    self.out.write(f'{indent}{self._escape_text(content)}\n'.encode('utf-8'))
            
            def close(self):
                if hasattr(self, 'out') and self.out:
                    self.out.close()
            
            def _escape_attr(self, text):
                """Escape XML attribute values."""
                if not isinstance(text, str):
                    text = str(text)
                return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
            
            def _escape_text(self, text):
                """Escape XML text content."""
                if not isinstance(text, str):
                    text = str(text)
                return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        
        # Create our handler
        handler = OSMHandler(output_file)
        
        # Print information about relations to be skipped
        if handler.skip_relations:
            print(f"Relations to be skipped: {', '.join(handler.skip_relations)}")
        
        # Use iterparse for streaming processing
        print("Starting XML parsing...")
        context = etree.iterparse(input_file, events=('start', 'end'))
        
        # Process the XML file
        for i, (event, elem) in enumerate(context):
            # Update element counter and show progress
            if event == 'start':
                total_elements += 1
                if total_elements % 100000 == 0:
                    elapsed = time.time() - start_time
                    print(f"Progress: Processed {total_elements:,} elements in {elapsed:.1f} seconds")
                    print(f"Modified ways so far: {handler.modified_count}")
            
            # Process the element
            if event == 'start':
                # Convert lxml Element to attributes dict
                attrs = dict(elem.attrib)
                handler.start_element(elem.tag, attrs)
                
                # Handle text content
                if elem.text and elem.text.strip():
                    handler.handle_text(elem.text)
            
            elif event == 'end':
                handler.end_element(elem.tag)
                
                # Clear element to save memory
                elem.clear()
                # Also eliminate now-empty references from the root node to elem
                while elem.getprevious() is not None:
                    del elem.getparent()[0]
        
        # Close the handler
        handler.close()
        
        # Update modified_ways count
        modified_ways = handler.modified_count
        
        # Report statistics
        elapsed_time = time.time() - start_time
        print("\nProcessing complete!")
        print("Statistics:")
        print(f"  - Total elements processed: {total_elements:,}")
        print(f"  - Ways modified: {modified_ways}")
        print(f"  - Relations skipped: {handler.skipped_relations}")
        print(f"  - Processing time: {elapsed_time:.2f} seconds")
        print(f"  - Processing speed: {total_elements / elapsed_time:.2f} elements/second")
        print(f"  - Input file size: {file_size / (1024*1024):.2f} MB")
        print(f"  - Output file size: {os.path.getsize(output_file) / (1024*1024):.2f} MB")
        
        return True
    
    except Exception as e:
        print(f"Error processing file: {str(e)}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <input_osm_file> <output_osm_file> [relation_id1,relation_id2,...]")
        print("Example: python fix_private_roads.py input.osm output.osm 1963216,123456,789012")
        print("The script will always skip relation 1963216 by default")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2]
    
    # Parse additional relations to skip
    additional_skip_relations = []
    if len(sys.argv) > 3:
        relation_ids = sys.argv[3].split(',')
        additional_skip_relations = [rid.strip() for rid in relation_ids if rid.strip()]
    
    if process_osm_file(input_file, output_file, additional_skip_relations):
        print("OSM file processing completed successfully.")
    else:
        print("OSM file processing failed.")
        sys.exit(1)
