#!/usr/bin/env python3
"""Builds the example expansion banjotooie.exp (Banjo-Tooie USA) from the repo's art + tables."""
import json, re, sys, zipfile, os

HERE = os.path.dirname(os.path.abspath(__file__))
ART = HERE + '/art/'
FONT = ART + 'LilitaOne-Regular.ttf'
OUT = sys.argv[1] if len(sys.argv) > 1 else 'banjotooie.exp'

# Banjo-Tooie (USA) tables: flags are (byte << 3) | bit in the flag block (from the Archipelago
# Banjo-Tooie connector, MIT)
TABLES = json.load(open(HERE + '/banjotooie_tables.json'))
def arr(name): return TABLES[name]
def arr2(name): return TABLES[name]
worlds = TABLES['WORLDS']
map_world = {a: b for a, b in TABLES['MAP_WORLD']}

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

# --- Values for the map page: this world's collectibles -------------------------------------
J, NN, TC = arr('JIGGY_FLAGS'), arr('NOTE_NEST_FLAGS'), arr('TREBLE_CLEF_FLAGS')
HC, PG, MV = arr2('WORLD_HONEYCOMB_FLAGS'), arr2('WORLD_PAGE_FLAGS'), arr2('WORLD_MOVE_FLAGS')
values["world_idx"] = {"type": "lookup", "value": "map", "keep_last": True, "default": -1,
                       "table": {"0x%X" % k: v for k, v in sorted(map_world.items())}}
for i in range(9):
    jb = J[i*10:(i+1)*10] if i < 8 else J[80:] + arr('JINJO_FAMILY_JIGGY_FLAGS')
    values["w%d_jiggies" % i] = dict(type="flags", bits=jb, **FLAGS)
    values["w%d_nests" % i] = dict(type="flags", bits=NN[i*16:(i+1)*16], **FLAGS)
    values["w%d_clef" % i] = dict(type="flags", bits=[TC[i]], **FLAGS)
    values["w%d_notes" % i] = {"type": "sum", "terms": [{"value": "w%d_nests" % i, "times": 5}, {"value": "w%d_clef" % i, "times": 20}]}
    values["w%d_honey" % i] = dict(type="flags", bits=HC[i], **FLAGS)
    values["w%d_pages" % i] = dict(type="flags", bits=PG[i], **FLAGS)
    values["w%d_moves" % i] = dict(type="flags", bits=MV[i], **FLAGS)
    values["w%d_jiggies_max" % i] = {"type": "const", "value": len(jb)}
    values["w%d_honey_max" % i] = {"type": "const", "value": len(HC[i])}
    values["w%d_pages_max" % i] = {"type": "const", "value": len(PG[i])}
    values["w%d_moves_max" % i] = {"type": "const", "value": len(MV[i])}
for name in ["jiggies", "notes", "honey", "pages", "moves", "jiggies_max", "honey_max", "pages_max", "moves_max"]:
    values["here_" + name] = {"type": "select", "index": "world_idx", "options": ["w%d_%s" % (i, name) for i in range(9)], "default": "-"}

# Player position: player pointer table [0x80135490 + 4 * index] -> +0xE4 -> x, y, z (floats)
values["player_index"] = {"type": "u8", "addr": "0x801354DF"}
for axis, off in (("x", "0"), ("y", "4"), ("z", "8")):
    values["pos_" + axis] = {"type": "f32", "chain": ["0x80135490", {"value": "player_index", "times": 4}, "*", "0xE4", "*", off]}

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
    "map_screen": {
        "template": "images/map_template.jpg",
        "mask": "images/map_mask.png",
        "fill": "#2A1709",
        "title": {"x": 205, "y": 120, "w": 320, "size": 58, "value": "{world}"},
        "map": {
            "rect": [228, 58, 1158, 892],
            "zoom": 2.0,
            "map_value": "map",
            "x": "pos_x", "z": "pos_z",
            "objects": {"list": "0x80136EE0", "first": 4, "last": 8, "base": 16, "stride": 156, "x": 4, "z": 12},
            "images": [
                {"image": "maps/mayahem_temple.png", "ids": ["0xB8"], "center": [0.5, 0.45]}
            ]
        },
        "slots": [
            {"x": 110, "y": 270, "r": 72, "icon": "icons/jiggy.png", "value": "{here_jiggies}/{here_jiggies_max}"},
            {"x": 108, "y": 408, "r": 72, "icon": "icons/note.png", "value": "{here_notes}/100"},
            {"x": 106, "y": 545, "r": 72, "icon": "icons/honeycomb.png", "value": "{here_honey}/{here_honey_max}"},
            {"x": 102, "y": 682, "r": 72, "icon": "icons/page.png", "value": "{here_pages}/{here_pages_max}"},
            {"x": 98, "y": 820, "r": 72, "icon": "icons/moves.png", "value": "{here_moves}/{here_moves_max}"},
            {"x": 1293, "y": 360, "r": 100, "icon": "icons/feather_red.png", "value": "{red_feathers}"},
            {"x": 1300, "y": 550, "r": 100, "icon": "icons/feather_gold.png", "value": "{gold_feathers}"},
            {"x": 1315, "y": 752, "r": 100, "icon": "icons/egg_blue.png", "value": "{blue_eggs}"}
        ],
        "tabs": [
            {"x": 10, "y": 925, "w": 262, "h": 150, "label": "Painel", "action": "screen:main"},
            {"x": 280, "y": 925, "w": 282, "h": 150, "label": "Mapa", "action": "screen:map"},
            {"x": 573, "y": 925, "w": 284, "h": 150, "label": "Golpes", "action": "screen:main"},
            {"x": 862, "y": 925, "w": 282, "h": 150, "label": "Opções", "action": "menu"},
            {"x": 1150, "y": 925, "w": 275, "h": 150, "label": "Salvar e sair", "action": "save_quit"}
        ]
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
# Map page art: the user's template (as JPEG), the parchment mask, the Mayahem Temple map
import numpy as np
from PIL import Image, ImageFilter
tpl = Image.open(HERE + '/map_template.png').convert('RGB')
tpl.save('/tmp/_map_template.jpg', quality=92)
a = np.array(tpl).astype(int); H_, W_ = a.shape[:2]
paper = (a[:, :, 0] > 175) & (a[:, :, 1] > 110) & (a[:, :, 2] < 190)
yy, xx = np.mgrid[0:H_, 0:W_]
paper &= (xx > 225) & (xx < 1160) & (yy < 900)
mk = Image.fromarray((paper * 255).astype('uint8')).filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(15)).filter(ImageFilter.GaussianBlur(2))
mask = Image.new('RGBA', mk.size, (255, 255, 255, 0)); mask.putalpha(mk); mask.save('/tmp/_map_mask.png', optimize=True)
Image.open(HERE + '/mayahem_cut.png').save('/tmp/_mayahem.png', optimize=True)
EXTRA = {"images/map_template.jpg": "/tmp/_map_template.jpg", "images/map_mask.png": "/tmp/_map_mask.png",
         "maps/mayahem_temple.png": "/tmp/_mayahem.png"}

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    for dst, src in files.items():
        z.write(ART + src, dst)
    z.write(FONT, "fonts/LilitaOne-Regular.ttf")
    for dst, src in EXTRA.items():
        z.write(src, dst)
    z.write(ART + "LilitaOne-OFL.txt", "fonts/OFL.txt")
print(OUT, os.path.getsize(OUT), "bytes")
