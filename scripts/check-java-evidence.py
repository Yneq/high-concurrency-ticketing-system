#!/usr/bin/env python3
"""Allow load-only continuation only when this source tree has passing local Java evidence."""
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile
root=Path(__file__).resolve().parents[1]
jar=root/'target/high-concurrency-ticketing-system-0.0.1-SNAPSHOT.jar'
if not jar.is_file(): raise SystemExit('Run mvn verify first: packaged JAR is missing')
with ZipFile(jar) as archive:
    if 'BOOT-INF/classes/com/ticketing/finalphase/ReservationService.class' not in archive.namelist():
        raise SystemExit('Run mvn verify first: final application is not packaged')
sources=[root/'pom.xml',*list((root/'src/main').rglob('*')),*list((root/'src/test').rglob('*.java'))]
latest=max(p.stat().st_mtime for p in sources if p.is_file())
if jar.stat().st_mtime<latest: raise SystemExit('Sources changed after packaging; run mvn verify again')
for name in ['TicketIntegrationTest','FinalIntegrationTest']:
    path=root/f'target/surefire-reports/TEST-com.ticketing.{name}.xml'
    if not path.is_file(): raise SystemExit(f'Missing {name} evidence; run mvn verify')
    report=ET.parse(path).getroot()
    if int(report.attrib['tests'])<1 or any(int(report.attrib[k]) for k in ['failures','errors','skipped']):
        raise SystemExit(f'{name} did not fully pass; run mvn verify')
    if path.stat().st_mtime<latest: raise SystemExit(f'{name} report is older than sources; run mvn verify')
print('Passing integration reports and packaged JAR match current source timestamps; continue load verification')
