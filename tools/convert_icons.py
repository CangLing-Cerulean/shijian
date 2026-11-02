"""Convert the vendored Lucide 0.468.0 SVG primitives to native vector drawables."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

source, target = map(Path, sys.argv[1:])
target.mkdir(parents=True, exist_ok=True)
for file in source.glob('*.svg'):
    paths = []
    for node in ET.parse(file).getroot():
        tag = node.tag.split('}')[-1]
        a = node.attrib
        if tag == 'path': d = a['d']
        elif tag == 'line': d = f"M{a['x1']},{a['y1']} L{a['x2']},{a['y2']}"
        elif tag in ('polyline', 'polygon'):
            d = 'M' + a['points'].strip().replace(' ', ' L') + (' Z' if tag == 'polygon' else '')
        elif tag == 'circle':
            x, y, r = (float(a[k]) for k in ('cx', 'cy', 'r'))
            d = f'M{x-r},{y} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0'
        elif tag == 'rect':
            x, y, w, h = (float(a.get(k, 0)) for k in ('x','y','width','height'))
            r = min(float(a.get('rx', 0)), w/2, h/2)
            d = f'M{x+r},{y} H{x+w-r} Q{x+w},{y} {x+w},{y+r} V{y+h-r} Q{x+w},{y+h} {x+w-r},{y+h} H{x+r} Q{x},{y+h} {x},{y+h-r} V{y+r} Q{x},{y} {x+r},{y} Z'
        else: raise ValueError(f'Unsupported SVG primitive: {tag}')
        paths.append(f'    <path android:pathData="{escape(d)}" android:fillColor="@android:color/transparent" android:strokeColor="#FF000000" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    text = '<!-- Lucide 0.468.0, ISC license: licenses/Lucide.txt -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n' + '\n'.join(paths) + '\n</vector>\n'
    (target / ('ic_' + file.stem.replace('-', '_') + '.xml')).write_text(text, encoding='utf-8')
print(f'Converted {len(list(source.glob("*.svg")))} icons')
