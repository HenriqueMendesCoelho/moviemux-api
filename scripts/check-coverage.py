#!/usr/bin/env python3
"""Report module/layer coverage and enforce the measured unit-test ratchet."""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
report = root / 'target/site/jacoco/jacoco.xml'
if not report.exists():
    sys.exit('Missing unit coverage XML')
groups = {}
for package in ET.parse(report).getroot().findall('package'):
    name = package.get('name')
    parts = name.split('/')
    module = parts[2] if len(parts) > 2 else 'bootstrap'
    layer = next((layer for layer in ('usecase', 'domain', 'adapter') if '/' + layer in name), 'application')
    values = groups.setdefault(module + '/' + layer, {'LINE': [0, 0], 'BRANCH': [0, 0]})
    for counter in package.findall('counter'):
        kind = counter.get('type')
        if kind in values:
            values[kind][0] += int(counter.get('covered'))
            values[kind][1] += int(counter.get('covered')) + int(counter.get('missed'))
policy = json.loads((root / 'scripts/coverage-ratchet.json').read_text())
failures = []
for group, values in sorted(groups.items()):
    print(group, ', '.join(f'{kind}={covered}/{total}' for kind, (covered, total) in values.items()))
for group, minima in policy.items():
    if group not in groups:
        failures.append(f'Missing coverage group: {group}')
        continue
    for kind, minimum in minima.items():
        covered, total = groups[group][kind]
        if total == 0 or 100 * covered < minimum * total:
            failures.append(f'{group} {kind}: {covered}/{total} below {minimum}% measured floor')
if failures:
    sys.exit('\n'.join(failures))
