#!/usr/bin/env python3
"""Builds the example expansion banjotooie.exp (Banjo-Tooie USA) from the repo's art + tables."""
import json, re, sys, zipfile, os

REPO = '/root/mupen64plus-ae'
SRC = REPO + '/app/src/main/java/paulscode/android/mupen64plusae/game/BanjoTooieStats.java'
ART = REPO + '/app/src/main/res/drawable-nodpi/'
FONT = REPO + '/app/src/main/assets/fonts/LilitaOne-Regular.ttf'
OUT = sys.argv[1] if len(sys.argv) > 1 else 'banjotooie.exp'

java = open(SRC).read()

def arr(name):
    m = re.search(r'static final int\[\] ' + name + r'\s*=\s*\{(.*?)\};', java, re.S)
    return [int(x, 16) for x in re.findall(r'0x[0-9A-Fa-f]+', m.group(1))]

worlds = re.search(r'static final String\[\] WORLDS = \{(.*?)\};', java, re.S).group(1)
worlds = re.findall(r'"([^"]*)"', worlds)
mw = re.search(r'static final int\[\]\[\] MAP_WORLD = \{(.*?)\};', java, re.S).group(1)
map_world = {int(a, 16): int(b) for a, b in re.findall(r'\{(0x[0-9A-Fa-f]+),\s*(\d+)\}', mw)}

FLAGS = {"ptr": "0x8012C770", "offset": "0"}
def cons(i):
    return {"type": "u16", "addr": "0x%08X" % (0x8011B080 + i * 0x0C)}

values = {
    "map": {"type": "u16", "addr": "0x80132DC2"},
    "world": {"type": "lookup", "value": "map", "keep_last": True, "default": "Banjo-Tooie",
              "table": {"0x%X" % k: worlds[v] for k, v in sorted(map_world.items())}},
    "jiggies": dict(type="flags", bits=arr('JIGGY_FLAGS') + arr('JINJO_FAMILY_JIGGY_FLAGS'), **FLAGS),
    "nests": dict(type="flags", bits=arr('NOTE_NEST_FLAGS'), **FLAGS),
    "clefs": dict(type="flags", bits=arr('TREBLE_CLEF_FLAGS'), **FLAGS),
    "notes": {"type": "sum", "terms": [{"value": "nests", "times": 5}, {"value": "clefs", "times": 20}]},
    "moves": dict(type="flags", bits=[0xDA,0xDB,0xD9,0xDE,0xDF,0xE1,0xE0,0xEE,0xE2,0xE3,0xE4,0xEB,0xEC,0xED,
                                      0xE8,0xEA,0xE9,0xE7,0xE6,0xEF,0xF4,0xF2,0xF1,0xF3], **FLAGS),
    "honeycombs": cons(9), "pages": cons(10),
    "blue_eggs": cons(0), "fire_eggs": cons(1), "red_feathers": cons(6), "gold_feathers": cons(7),
}

manifest = {
    "format": 1,
    "id": "banjotooie-usa-exemplo",
    "name": "Banjo-Tooie – Exemplo",
    "game": "Banjo-Tooie (USA)",
    "version": "1.0",
    "author": "M64-DS",
    "match": {"header": "BANJO TOOIE", "country": "E"},
    "theme": {
        "background": "#24190F", "panel": "#6B3F1C", "text": "#FFFFFF", "label": "#F8E6C2", "accent": "#FFC93C",
        "title_colors": ["#3FA9F5", "#E8432E"],
        "font": "fonts/LilitaOne-Regular.ttf",
        "background_image": "images/bg.jpg",
        "sign_image": {"image": "images/sign.png", "insets": [300, 60, 300, 60]},
        "tile_image": {"image": "images/tile.png", "insets": [56, 80, 56, 52], "label_y": 40, "value_y": 42},
        "button_image": {"image": "images/button.png", "insets": [40, 40, 40, 40]},
        "bar_image": {"image": "images/bar.png", "insets": [455, 30, 60, 30], "content_left": 470},
    },
    "screen": {
        "title": "{world}",
        "columns": 3,
        "tiles": [
            {"label": "Jiggies", "icon": "icons/jiggy.png", "value": "{jiggies}/90"},
            {"label": "Notas", "icon": "icons/note.png", "value": "{notes}/900"},
            {"label": "Golpes", "icon": "icons/moves.png", "value": "{moves}/24"},
            {"label": "Favos", "icon": "icons/honeycomb.png", "value": "{honeycombs}"},
            {"label": "Páginas Cheato", "icon": "icons/page.png", "value": "{pages}"},
            {"label": "Tempo", "icon": "icons/clock.png", "value": "{time}"},
        ],
        "bar": {"label": "", "items": [
            {"icon": "icons/feather_red.png", "value": "{red_feathers}"},
            {"icon": "icons/feather_gold.png", "value": "{gold_feathers}"},
            {"icon": "icons/egg_blue.png", "value": "{blue_eggs}"},
            {"icon": "icons/egg_fire.png", "value": "{fire_eggs}"},
        ]},
    },
    "values": values,
}

files = {
    "images/bg.jpg": "bt_bg.jpg", "images/sign.png": "bt_sign.png", "images/tile.png": "bt_tile.png",
    "images/bar.png": "bt_resources.png", "images/button.png": "bt_tab.png",
    "icons/moves.png": "bt_moves.png",
    "icons/jiggy.png": "bt_jiggy.png", "icons/note.png": "bt_note.png", "icons/honeycomb.png": "bt_honeycomb.png",
    "icons/page.png": "bt_page.png", "icons/clock.png": "bt_clock.png",
    "icons/feather_red.png": "bt_feather_red.png", "icons/feather_gold.png": "bt_feather_gold.png",
    "icons/egg_blue.png": "bt_egg_blue.png", "icons/egg_fire.png": "bt_egg_fire.png",
}
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    for dst, src in files.items():
        z.write(ART + src, dst)
    z.write(FONT, "fonts/LilitaOne-Regular.ttf")
    z.write(os.path.dirname(FONT) + "/LilitaOne-OFL.txt", "fonts/OFL.txt")
print(OUT, os.path.getsize(OUT), "bytes")
