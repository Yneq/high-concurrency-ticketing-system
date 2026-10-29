#!/usr/bin/env python3
"""Syntax/document consistency only; not an integration acceptance test."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
for path in [ROOT/'pom.xml', ROOT/'verification/core/pom.xml']:
    ET.parse(path)
spec=json.loads((ROOT/'docs/openapi.yaml').read_text())
assert spec['openapi']=='3.0.3'
def check_refs(value):
    if isinstance(value,dict):
        if '$ref' in value:
            target=spec
            for key in value['$ref'].removeprefix('#/').split('/'):
                target=target[key]
        for nested in value.values(): check_refs(nested)
    elif isinstance(value,list):
        for nested in value: check_refs(nested)
check_refs(spec)
for path in [ROOT/'README.md', *list((ROOT/'docs').glob('*.md'))]:
    # Historic V1 document links describe the archived original layout.
    if path.name in ('v1-readme.md','v1-validation.md'): continue
    for target in re.findall(r'\]\(([^)]+)\)',path.read_text()):
        if '://' not in target and not target.startswith('#'):
            assert (path.parent/target.split('#')[0]).exists(), (path,target)
print('PASS: both Maven XML files, OpenAPI JSON/YAML internal references, current Markdown local links')
