#!/usr/bin/env python3
"""Fail closed when a verification run has missing, skipped or failed tests."""
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
for directory in ('surefire-reports', 'failsafe-reports'):
    reports = list((root / 'target' / directory).glob('TEST-*.xml'))
    if not reports:
        sys.exit(f'Missing test reports: {directory}')
    counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, '0'))
    print(f'{directory}: {counts}')
    if counts['tests'] == 0 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
        sys.exit(f'Test collection or result gate failed: {directory}')
