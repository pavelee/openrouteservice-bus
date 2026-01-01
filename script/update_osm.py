#!/usr/bin/env python3
"""
Automated OSM Map Update Script
Automates the process of downloading and processing OpenStreetMap data for the Mazowieckie region.

This script performs the following steps:
1. Downloads the latest mazowieckie-latest.osm.pbf from Geofabrik
2. Converts PBF to XML format using convert_osm_to_xml.py
3. Processes private roads using fix_private_roads.py
4. Replaces the old map file in ors-docker/files directory
"""

import os
import sys
import subprocess
import shutil
import requests
from datetime import datetime
import argparse
from pathlib import Path


class OSMUpdateLogger:
    """Simple logger with timestamps and step tracking."""
    
    def __init__(self, verbose=False):
        self.verbose = verbose
        self.step_counter = 0
        
    def _timestamp(self):
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    def info(self, message):
        print(f"[{self._timestamp()}] [INFO] {message}")
        
    def step(self, message):
        self.step_counter += 1
        print(f"[{self._timestamp()}] [STEP {self.step_counter}] {message}")
        
    def success(self, message):
        print(f"[{self._timestamp()}] [SUCCESS] {message}")
        
    def error(self, message):
        print(f"[{self._timestamp()}] [ERROR] {message}")
        
    def debug(self, message):
        if self.verbose:
            print(f"[{self._timestamp()}] [DEBUG] {message}")


class OSMUpdater:
    """Main class for handling OSM map updates."""
    
    def __init__(self, verbose=False, dry_run=False):
        self.logger = OSMUpdateLogger(verbose)
        self.dry_run = dry_run
        self.script_dir = Path(__file__).parent
        self.ors_docker_files = self.script_dir.parent / "ors-docker" / "files"
        
        # File paths
        self.pbf_file = self.script_dir / "mazowieckie-latest.osm.pbf"
        self.xml_file = self.script_dir / "mazowieckie-latest.osm"
        self.processed_file = self.script_dir / "mazowieckie.osm"
        self.target_file = self.ors_docker_files / "mazowieckie.osm"
        
        # URLs and commands
        self.download_url = "https://download.geofabrik.de/europe/poland/mazowieckie-latest.osm.pbf"
        self.convert_script = self.script_dir / "convert_osm_to_xml.py"
        self.fix_script = self.script_dir / "fix_private_roads.py"
        self.venv_activate = self.script_dir / "env" / "bin" / "activate"
        
    def download_osm_data(self):
        """Download the latest OSM data from Geofabrik."""
        self.logger.step("Downloading latest OSM data from Geofabrik")
        
        # Remove existing PBF file if it exists
        if self.pbf_file.exists():
            self.logger.info(f"Removing existing file: {self.pbf_file}")
            if not self.dry_run:
                self.pbf_file.unlink()
        
        if self.dry_run:
            self.logger.info(f"[DRY RUN] Would download from {self.download_url}")
            return True
            
        try:
            self.logger.info(f"Downloading from: {self.download_url}")
            response = requests.get(self.download_url, stream=True)
            response.raise_for_status()
            
            total_size = int(response.headers.get('content-length', 0))
            downloaded = 0
            
            with open(self.pbf_file, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total_size > 0:
                            progress = (downloaded / total_size) * 100
                            print(f"\r[{self.logger._timestamp()}] [PROGRESS] Downloaded: {progress:.1f}%", end='')
            
            print()  # New line after progress
            file_size_mb = self.pbf_file.stat().st_size / (1024 * 1024)
            self.logger.success(f"Downloaded successfully: {file_size_mb:.2f} MB")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to download OSM data: {e}")
            return False
    
    def convert_to_xml(self):
        """Convert PBF file to XML using convert_osm_to_xml.py."""
        self.logger.step("Converting PBF to XML format")
        
        if not self.pbf_file.exists():
            self.logger.error(f"PBF file not found: {self.pbf_file}")
            return False
            
        # Remove existing XML file if it exists
        if self.xml_file.exists():
            self.logger.info(f"Removing existing XML file: {self.xml_file}")
            if not self.dry_run:
                self.xml_file.unlink()
        
        if self.dry_run:
            self.logger.info(f"[DRY RUN] Would run: {self.convert_script}")
            return True
            
        try:
            # Make sure the script is executable
            os.chmod(self.convert_script, 0o755)
            
            # Run the conversion script
            self.logger.info(f"Running: {self.convert_script}")
            result = subprocess.run(
                [str(self.convert_script)],
                cwd=self.script_dir,
                capture_output=True,
                text=True
            )
            
            if result.returncode == 0:
                if self.xml_file.exists():
                    file_size_mb = self.xml_file.stat().st_size / (1024 * 1024)
                    self.logger.success(f"Conversion completed: {file_size_mb:.2f} MB")
                    return True
                else:
                    self.logger.error("Conversion script completed but XML file not found")
                    return False
            else:
                self.logger.error(f"Conversion failed with return code {result.returncode}")
                if result.stderr:
                    self.logger.error(f"Error output: {result.stderr}")
                return False
                
        except Exception as e:
            self.logger.error(f"Failed to run conversion script: {e}")
            return False
    
    def process_private_roads(self):
        """Process private roads using fix_private_roads.py."""
        self.logger.step("Processing private roads and restrictions")
        
        if not self.xml_file.exists():
            self.logger.error(f"XML file not found: {self.xml_file}")
            return False
            
        # Remove existing processed file if it exists
        if self.processed_file.exists():
            self.logger.info(f"Removing existing processed file: {self.processed_file}")
            if not self.dry_run:
                self.processed_file.unlink()
        
        if self.dry_run:
            self.logger.info(f"[DRY RUN] Would run: {self.fix_script} {self.xml_file} {self.processed_file}")
            return True
            
        try:
            # Make sure the script is executable
            os.chmod(self.fix_script, 0o755)
            
            # Prepare command with virtual environment activation
            cmd = f"source {self.venv_activate} && {self.fix_script} {self.xml_file} {self.processed_file}"
            
            self.logger.info(f"Running: {self.fix_script} with virtual environment")
            result = subprocess.run(
                cmd,
                shell=True,
                cwd=self.script_dir,
                capture_output=True,
                text=True
            )
            
            if result.returncode == 0:
                if self.processed_file.exists():
                    file_size_mb = self.processed_file.stat().st_size / (1024 * 1024)
                    self.logger.success(f"Processing completed: {file_size_mb:.2f} MB")
                    return True
                else:
                    self.logger.error("Processing script completed but output file not found")
                    return False
            else:
                self.logger.error(f"Processing failed with return code {result.returncode}")
                if result.stderr:
                    self.logger.error(f"Error output: {result.stderr}")
                if result.stdout:
                    self.logger.info(f"Script output: {result.stdout}")
                return False
                
        except Exception as e:
            self.logger.error(f"Failed to run processing script: {e}")
            return False
    
    def deploy_to_ors(self):
        """Deploy the processed file to ors-docker/files directory."""
        self.logger.step("Deploying processed file to ORS directory")
        
        if not self.processed_file.exists():
            self.logger.error(f"Processed file not found: {self.processed_file}")
            return False
            
        # Create backup of existing file if it exists
        if self.target_file.exists():
            backup_file = self.ors_docker_files / f"mazowieckie.osm.backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
            self.logger.info(f"Creating backup: {backup_file}")
            if not self.dry_run:
                shutil.copy2(self.target_file, backup_file)
        
        if self.dry_run:
            self.logger.info(f"[DRY RUN] Would move {self.processed_file} to {self.target_file}")
            return True
            
        try:
            # Ensure target directory exists
            self.ors_docker_files.mkdir(parents=True, exist_ok=True)
            
            # Move the processed file to target location
            self.logger.info(f"Moving {self.processed_file} to {self.target_file}")
            shutil.move(str(self.processed_file), str(self.target_file))
            
            file_size_mb = self.target_file.stat().st_size / (1024 * 1024)
            self.logger.success(f"Deployment completed: {file_size_mb:.2f} MB")
            return True
            
        except Exception as e:
            self.logger.error(f"Failed to deploy file: {e}")
            return False
    
    def cleanup_temp_files(self):
        """Clean up temporary files."""
        self.logger.step("Cleaning up temporary files")
        
        temp_files = [self.pbf_file, self.xml_file]
        
        for temp_file in temp_files:
            if temp_file.exists():
                self.logger.info(f"Removing temporary file: {temp_file}")
                if not self.dry_run:
                    temp_file.unlink()
        
        self.logger.success("Cleanup completed")
    
    def run_update(self):
        """Run the complete update process."""
        self.logger.info("Starting OSM map update process")
        start_time = datetime.now()
        
        try:
            # Step 1: Download
            if not self.download_osm_data():
                return False
                
            # Step 2: Convert
            if not self.convert_to_xml():
                return False
                
            # Step 3: Process
            if not self.process_private_roads():
                return False
                
            # Step 4: Deploy
            if not self.deploy_to_ors():
                return False
                
            # Step 5: Cleanup
            self.cleanup_temp_files()
            
            # Summary
            elapsed = datetime.now() - start_time
            self.logger.success(f"OSM map update completed successfully in {elapsed}")
            return True
            
        except KeyboardInterrupt:
            self.logger.error("Update process interrupted by user")
            return False
        except Exception as e:
            self.logger.error(f"Unexpected error during update: {e}")
            return False


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Automated OSM Map Update Script")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable verbose logging")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be done without executing")
    
    args = parser.parse_args()
    
    updater = OSMUpdater(verbose=args.verbose, dry_run=args.dry_run)
    success = updater.run_update()
    
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()