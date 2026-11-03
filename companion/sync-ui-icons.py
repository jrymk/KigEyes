"""Import the companion's small Lucide subset from the canonical dashboard SVGs."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'visor/dashboard/icons'
MAIN=Path(__file__).resolve().parent/'app/src/main'

def paths(name):
    result=[]
    for el in ET.parse(SOURCE/(name+'.svg')).getroot():
        tag=el.tag.split('}')[-1];a=el.attrib
        if tag=='path':result.append(a['d'])
        elif tag=='circle':
            x,y,r=map(float,[a['cx'],a['cy'],a['r']]);result.append(f'M {x-r} {y} a {r} {r} 0 1 0 {2*r} 0 a {r} {r} 0 1 0 {-2*r} 0')
        elif tag=='polygon':
            points=a['points'].split();result.append('M '+' '.join(points[:2])+' L '+' '.join(points[2:])+' Z')
        elif tag=='rect':
            x,y,w,h=map(float,[a['x'],a['y'],a['width'],a['height']]);r=float(a.get('rx',0))
            result.append(f'M {x+r} {y} H {x+w-r} Q {x+w} {y} {x+w} {y+r} V {y+h-r} Q {x+w} {y+h} {x+w-r} {y+h} H {x+r} Q {x} {y+h} {x} {y+h-r} V {y+r} Q {x} {y} {x+r} {y} Z')
        else:raise ValueError('Unsupported SVG element: '+tag)
    return result

def vector(name,launcher=False):
    size=108 if launcher else 24
    text=f'<!-- Generated from dashboard/icons/{name}.svg; run sync-ui-icons.py. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n'
    if launcher:text+='    <group android:translateX="27" android:translateY="27" android:scaleX="2.25" android:scaleY="2.25">\n'
    tint='#FF9BE3AE' if launcher else '#FFFFFFFF'
    for data in paths(name):text+=f'    <path android:fillColor="#00000000" android:strokeColor="{tint}" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{data}" />\n'
    if launcher:text+='    </group>\n'
    return text+'</vector>\n'

for name in ('wifi','settings','camera','arrow-left','play','pause','eye','x'):
    (MAIN/'res/drawable'/('ic_home_'+name.replace('-','_')+'.xml')).write_text(vector(name),encoding='utf-8')
(MAIN/'res/drawable/ic_kigeyes_foreground.xml').write_text(vector('glasses',True),encoding='utf-8')
license=MAIN/'assets/licenses';license.mkdir(parents=True,exist_ok=True)
shutil.copy2(SOURCE/'LICENSE.lucide-0.468.0.txt',license/'Lucide.txt')
