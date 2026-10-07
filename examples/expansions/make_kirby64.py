#!/usr/bin/env python3
"""
Builds kirby64.exp (Kirby 64: The Crystal Shards, USA) from your own ROM.

    python3 make_kirby64.py "Kirby 64 - The Crystal Shards (USA).z64" [kirby64.exp] [preview.png]

All art is cut from the ROM's own HUD and menu sprites (the game's image banks), so the panel uses
the game's colours and fonts. Nothing from the ROM is stored in this repo. The page is a
"pixel_screen": a 248x199 canvas that the emulator scales up by a whole number, with no smoothing.

Memory addresses come from the Kirby 64 decompilation (github.com/farisawan-2000/kirby64), which
matches the USA ROM (sha1 6cea2d46b929a3bb347b060a77fccc83526fb855).
"""
import io, json, struct, sys, zipfile, hashlib
from PIL import Image, ImageDraw

ROM_PATH = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else 'kirby64.exp'
PREVIEW = sys.argv[3] if len(sys.argv) > 3 else None

rom = open(ROM_PATH, 'rb').read()
if rom[:4] == b'\x37\x80\x40\x12':                      # .v64 (byte-swapped) -> .z64
    rom = b''.join(rom[i + 1:i + 2] + rom[i:i + 1] for i in range(0, len(rom), 2))
if rom[0x3B:0x3F] != b'NK4E':
    sys.exit('This is not Kirby 64 (USA): game id %r' % rom[0x3B:0x3F])

# ---------------------------------------------------------------------------------------------
# The game's image files. ovl1 is loaded at 0x8009B540 from ROM 0x43790; gFileTable (0x800D0184)
# holds 8 bank headers, each with an image offset table and the ROM base of its images.
# ---------------------------------------------------------------------------------------------
def v2r(v): return 0x43790 + (v - 0x8009B540)
def u32(a): return struct.unpack('>I', rom[a:a + 4])[0]
BANKS = [u32(v2r(0x800D0184) + 4 * i) for i in range(8)]

def image_file(bank, index):
    h = struct.unpack('>8I', rom[v2r(BANKS[bank]):v2r(BANKS[bank]) + 32])
    t = v2r(h[2]) + 4 * index
    return h[3] + u32(t)

def rgba16(v):
    return ((v >> 11 & 31) * 255 // 31, (v >> 6 & 31) * 255 // 31, (v >> 1 & 31) * 255 // 31, 255 if v & 1 else 0)

def sprite(bank, index):
    """Decode one image file: 16-byte header (fmt, size, -, -, w, h, data offset, palette offset)."""
    a = image_file(bank, index)
    fmt, siz = rom[a], rom[a + 1]
    w, h = struct.unpack('>HH', rom[a + 4:a + 8])
    bpp = [4, 8, 16, 32][siz]
    stride = ((w * bpp + 7) // 8 + 7) // 8 * 8          # rows are padded to 8 bytes
    pal = None
    if fmt == 2:
        po = u32(a + 12)
        n = 16 if bpp == 4 else 256
        pal = [rgba16(struct.unpack('>H', rom[a + po + 2 * i:a + po + 2 * i + 2])[0]) for i in range(n)]
    px = []
    for y in range(h):
        row = rom[a + 16 + y * stride:a + 16 + (y + 1) * stride]
        for x in range(w):
            v = (row[x // 2] >> 4 if x % 2 == 0 else row[x // 2] & 15) if bpp == 4 else \
                row[x] if bpp == 8 else (row[2 * x] << 8 | row[2 * x + 1]) if bpp == 16 else tuple(row[4 * x:4 * x + 4])
            if fmt == 0: px.append(rgba16(v) if bpp == 16 else v)
            elif fmt == 2: px.append(pal[v])
            elif fmt == 3:
                px.append(((v >> 1) * 36,) * 3 + (255 if v & 1 else 0,) if bpp == 4 else ((v >> 4) * 17,) * 3 + ((v & 15) * 17,))
            else:
                c = v * 17 if bpp == 4 else v
                px.append((c, c, c, c))
    im = Image.new('RGBA', (w, h))
    im.putdata(px)
    return im

# --- Sprites used (bank 5 = HUD, wood theme; bank 3 = menus) --------------------------------
frame = sprite(5, 0x01)                                   # 320x48 HUD bar, wood theme
digit = {d: sprite(5, 0xBF + d) for d in range(10)}       # HUD digits, wood theme (18x30)
cell = sprite(5, 0xA2)                                    # full health cell
star = sprite(5, 0xAC)                                    # star meter segment, lit (pale yellow)
ICON_IDS = [0xF1, 0xF2, 0xF3, 0xF8, 0xF4, 0xF5, 0xF6, 0xF7, 0xF9, 0xFA]   # D_800D5310, theme 0
icon = {i: sprite(5, ICON_IDS[i]) for i in range(1, 10)}  # 1 fire .. 7 cutter, 8-9 specials
world_name = {w: sprite(3, i) for w, i in enumerate([0x1F6, 0x1FC, 0x1FB, 0x1F8, 0x1F7, 0x1F9, 0x1FA])}
level_word = sprite(3, 0x1FD)                             # "Level"
level_num = {n: sprite(3, 0x1FE + n) for n in range(1, 8)} # outlined 1..7
small = {d: sprite(3, 0x1C2 + d) for d in range(10)}      # outlined small digits
small_slash = sprite(3, 0x1CC)

# HUD ability index (0..37) -> left/right element icon (table D_800D55F8, 2 bytes each)
pairs = rom[v2r(0x800D55F8):v2r(0x800D55F8) + 2 * 38]
AB_L = {i: pairs[2 * i] for i in range(38)}
AB_R = {i: pairs[2 * i + 1] for i in range(38)}

# ---------------------------------------------------------------------------------------------
# Static art: one background with everything that never changes
# ---------------------------------------------------------------------------------------------
GW, GH = 248, 199
DARK, EDGE = (106, 74, 8, 255), (246, 197, 98, 255)
X0 = (GW - 206) // 2                                       # left edge of the content (21)
Y_HUD, Y_WORLD, Y_ABIL, Y_TOTAL = 4, 58, 90, 144

def rounded(d, x, y, w, h, fill):
    d.rectangle((x + 2, y, x + w - 3, y + h - 1), fill=fill)
    d.rectangle((x, y + 2, x + w - 1, y + h - 3), fill=fill)
    d.rectangle((x + 1, y + 1, x + w - 2, y + h - 2), fill=fill)

SHARD = ["...a...", "..aba..", ".abbca.", ".abbca.", "abbbcca", "abbbcca", ".abbca.", ".abcca.", "..aca..", "...a..."]
def shard_img(got):
    pal = {'a': (40, 72, 160, 255), 'b': (170, 230, 255, 255), 'c': (90, 170, 240, 255)} if got else \
          {'a': (70, 48, 6, 255), 'b': (84, 58, 8, 255), 'c': (84, 58, 8, 255)}
    im = Image.new('RGBA', (7, 10))
    for j, row in enumerate(SHARD):
        for i, ch in enumerate(row):
            if ch != '.': im.putpixel((i, j), pal[ch])
    return im
SHARD_ON, SHARD_OFF = shard_img(True), shard_img(False)
SHARD_POS = [(3, 18), (14, 18), (8, 32)]                   # inside a stage box

def stage_box(n):
    im = Image.new('RGBA', (24, 44))
    rounded(ImageDraw.Draw(im), 0, 0, 24, 44, DARK)
    im.alpha_composite(small[n], (7, 3))
    for (sx, sy) in SHARD_POS: im.alpha_composite(SHARD_OFF, (sx, sy))
    return im

bg = Image.new('RGBA', (GW, GH))
stripe = frame.crop((12, 2, 13, 24)).resize((GW, 22), Image.NEAREST)  # the HUD's wood stripes
for y in range(0, GH, 22): bg.paste(stripe, (0, y))
d = ImageDraw.Draw(bg)
bg.alpha_composite(frame.crop((10, 1, 216, 45)), (X0, Y_HUD))          # lives box + health + stars
rounded(d, X0, Y_WORLD, 206, 26, DARK)                                  # world strip
bg.alpha_composite(level_word, (X0 + 132, Y_WORLD + 6))
bg.alpha_composite(frame.crop((216, 1, 310, 45)), (X0, Y_ABIL))          # ability slot, as in the HUD
for k in range(3): bg.alpha_composite(stage_box(k + 1), (X0 + 100 + k * 27, Y_ABIL + 1))
rounded(d, X0, Y_TOTAL, 206, 48, DARK)                                  # totals
for (sx, sy) in [(10, 8), (20, 8), (15, 24)]: bg.alpha_composite(SHARD_ON, (X0 + sx, Y_TOTAL + sy))
TOT_SLASH_X = X0 + 120
bg.alpha_composite(small_slash.resize((16, 24), Image.NEAREST), (TOT_SLASH_X, Y_TOTAL + 12))
bg.alpha_composite(digit[7], (TOT_SLASH_X + 20, Y_TOTAL + 9))
bg.alpha_composite(digit[4], (TOT_SLASH_X + 40, Y_TOTAL + 9))

ring = Image.new('RGBA', (26, 46))
rounded(ImageDraw.Draw(ring), 0, 0, 26, 46, EDGE)
ImageDraw.Draw(ring).rectangle((1, 1, 24, 44), fill=(0, 0, 0, 0))       # just the gold edge

# ---------------------------------------------------------------------------------------------
# Values (addresses from the decomp's symbol list)
# ---------------------------------------------------------------------------------------------
values = {
    "lives": {"type": "u32", "addr": "0x800D6E4C"},                     # gKirbyLives (0..100)
    "hp": {"type": "f32", "addr": "0x800D6E50"},                        # gKirbyHp (0..6)
    "stars": {"type": "u32", "addr": "0x800D6E60"},                     # gKirbyStars (30 = 1UP)
    "ability": {"type": "u32", "addr": "0x800D6E90"},                   # ability shown on the HUD
    "ability_l": {"type": "lookup", "value": "ability", "default": 0, "table": {str(k): v for k, v in AB_L.items()}},
    "ability_r": {"type": "lookup", "value": "ability", "default": 0, "table": {str(k): v for k, v in AB_R.items()}},
    "world": {"type": "u32", "addr": "0x800D6B98"},                     # 0 Pop Star .. 5 Ripple Star, 6 Dark Star
    "stage": {"type": "u32", "addr": "0x800D6B9C"},                     # 0-based stage in the world
    "is_dark": {"type": "lookup", "value": "world", "default": 0, "table": {"6": 1}},
    "has4": {"type": "lookup", "value": "world", "default": 1, "table": {"0": 0, "5": 0, "6": 0}},
    "world_no": {"type": "u32", "addr": "0x800D6B98", "add": 1},
    # Shards: one byte per stage at 0x800D6BC8 + world*4 + stage, bits 0-2 = the 3 shards
    "stage_shards": {"type": "flags", "addr": "0x800D6BC8",
                     "bits": [(w * 4 + s) << 3 | b for w in range(6) for s in range(4 if w not in (0, 5) else 3) for b in range(3)]},
    "boss_shards": {"type": "flags", "addr": "0x800D6BC0", "bits": [w << 3 for w in range(6)]},
    "shards": {"type": "sum", "terms": ["stage_shards", "boss_shards", {"value": "intro_shards", "times": 1}]},
    "intro_shards": {"type": "const", "value": 2},
}
for k in range(4):
    values["cur%d" % k] = {"type": "lookup", "value": "stage", "default": 0, "table": {str(k): 1}}
    for j in range(3):
        values["s%d_%d" % (k, j)] = {"type": "flags", "mode": "any", "bits": [j],
                                     "chain": ["0x800D6BC8", {"value": "world", "times": 4}, str(k)]}

# ---------------------------------------------------------------------------------------------
# Layers (canvas pixels)
# ---------------------------------------------------------------------------------------------
L = [{"image": "images/bg.png", "x": 0, "y": 0}]
L.append({"number": "lives", "glyphs": "digits/{c}.png", "pad": 2, "advance": 20, "x": X0 + 22, "y": Y_HUD + 8})
L.append({"repeat": "images/cell.png", "count": "hp", "max": 6, "dx": 20, "x": X0 + 80, "y": Y_HUD + 2})
L.append({"repeat": "images/star.png", "count": "stars", "max": 30, "dx": 4, "x": X0 + 80, "y": Y_HUD + 32})
L.append({"pick": "world", "images": {str(w): "images/world_%d.png" % w for w in range(7)}, "x": X0 + 8, "y": Y_WORLD + 3})
L.append({"pick": "world_no", "images": {str(n): "images/level_%d.png" % n for n in range(1, 8)}, "x": X0 + 178, "y": Y_WORLD + 6})
L.append({"pick": "ability_l", "images": {str(i): "icons/ab_%d.png" % i for i in range(1, 10)}, "anchor": "center", "x": X0 + 25, "y": Y_ABIL + 21})
L.append({"pick": "ability_r", "images": {str(i): "icons/ab_%d.png" % i for i in range(1, 10)}, "anchor": "center", "x": X0 + 60, "y": Y_ABIL + 21})
L.append({"image": "images/stage4.png", "x": X0 + 100 + 3 * 27, "y": Y_ABIL + 1, "show": "has4"})
for k in range(4):
    bx = X0 + 100 + k * 27
    L.append({"image": "images/ring.png", "x": bx - 1, "y": Y_ABIL, "show": "cur%d" % k, "hide": "is_dark"})
    for j, (sx, sy) in enumerate(SHARD_POS):
        L.append({"image": "icons/shard.png", "x": bx + sx, "y": Y_ABIL + 1 + sy, "show": "s%d_%d" % (k, j), "hide": "is_dark"})
L.append({"number": "shards", "glyphs": "digits/{c}.png", "advance": 20, "align": "right", "x": TOT_SLASH_X - 2, "y": Y_TOTAL + 9})

md5 = hashlib.md5(rom).hexdigest()
manifest = {
    "format": 1,
    "id": "kirby64-usa",
    "name": "Kirby 64 – The Crystal Shards",
    "game": "Kirby 64: The Crystal Shards (USA)",
    "version": "1.0",
    "author": "Hydriostatic",
    "match": {"header": "KIRBY64", "country": "E"},
    "theme": {"background": "#9C7318"},
    "screen": {"title": "Kirby 64"},
    "pixel_screen": {"size": [GW, GH], "fill": "#9C7318", "layers": L},
    "values": values,
}

files = {
    "images/bg.png": bg, "images/cell.png": cell, "images/star.png": star, "images/ring.png": ring,
    "images/stage4.png": stage_box(4), "icons/shard.png": SHARD_ON,
}
for w, im in world_name.items(): files["images/world_%d.png" % w] = im
for n, im in level_num.items(): files["images/level_%d.png" % n] = im
for i, im in icon.items(): files["icons/ab_%d.png" % i] = im
for n, im in digit.items(): files["digits/%d.png" % n] = im

def png(im):
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()

with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=1, ensure_ascii=False))
    for path, im in files.items(): z.writestr(path, png(im))
print('wrote', OUT, '(%d files)' % (len(files) + 1))

# ---------------------------------------------------------------------------------------------
# Optional preview: draws the pixel_screen the way the emulator does, with sample values
# ---------------------------------------------------------------------------------------------
if PREVIEW:
    sample = {"lives": 3, "hp": 4, "stars": 17, "ability": 14, "world": 1, "stage": 1, "world_no": 2,
              "shards": 23, "is_dark": 0, "has4": 1}
    sample["ability_l"], sample["ability_r"] = AB_L[sample["ability"]], AB_R[sample["ability"]]
    shard_bits = {0: 7, 1: 5, 2: 0, 3: 0}
    for k in range(4):
        sample["cur%d" % k] = 1 if sample["stage"] == k else 0
        for j in range(3): sample["s%d_%d" % (k, j)] = (shard_bits[k] >> j) & 1
    canvas = Image.new('RGBA', (GW, GH))
    def put(path, x, y, center=False):
        im = files[path]
        if center: x, y = x - im.width // 2, y - im.height // 2
        canvas.alpha_composite(im, (int(x), int(y)))
    for ly in L:
        if 'show' in ly and not sample.get(ly['show']): continue
        if 'hide' in ly and sample.get(ly['hide']): continue
        c = ly.get('anchor') == 'center'
        if 'image' in ly: put(ly['image'], ly['x'], ly['y'], c)
        elif 'repeat' in ly:
            for i in range(max(0, min(ly['max'], sample[ly['count']]))): put(ly['repeat'], ly['x'] + i * ly.get('dx', 0), ly['y'] + i * ly.get('dy', 0), c)
        elif 'pick' in ly:
            p = ly['images'].get(str(sample[ly['pick']]))
            if p: put(p, ly['x'], ly['y'], c)
        elif 'number' in ly:
            s = str(sample[ly['number']]).rjust(ly.get('pad', 0), '0')
            tot = ly['advance'] * len(s)
            x = ly['x'] - tot if ly.get('align') == 'right' else ly['x']
            for ch in s: put(ly['glyphs'].replace('{c}', ch), x, ly['y']); x += ly['advance']
    canvas.resize((GW * 5, GH * 5), Image.NEAREST).save(PREVIEW)
    print('preview', PREVIEW, '(ability %d = icons %d + %d)' % (sample["ability"], sample["ability_l"], sample["ability_r"]))
