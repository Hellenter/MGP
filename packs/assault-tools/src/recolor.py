"""Военные инструменты: силуэты ванильных алмазных инструментов, перекрашенные в военные цвета.
Запуск: python3 recolor.py <client.jar> <папка-выход>. Цвета ниже — по цветам ванильной палитры."""
import sys, zipfile, io
from PIL import Image

DIAMOND = {  # цвета ванильной алмазной палитры -> роль
    (14, 63, 54): 'outer', (51, 235, 203): 'light', (43, 199, 172): 'mid', (39, 178, 154): 'base',
    (30, 138, 119): 'dark', (8, 37, 32): 'shadow',
    (73, 54, 21): 'h_mid', (104, 78, 30): 'h_base', (137, 103, 39): 'h_light', (40, 30, 11): 'h_dark',
}
OLIVE = {'h_mid': (62, 70, 34), 'h_base': (84, 94, 46), 'h_light': (110, 122, 62), 'h_dark': (38, 42, 20)}
WOOD = {'h_mid': (78, 56, 30), 'h_base': (106, 78, 42), 'h_light': (136, 102, 58), 'h_dark': (44, 32, 16)}
CORD = {'h_mid': (34, 34, 32), 'h_base': (48, 48, 46), 'h_light': (66, 66, 62), 'h_dark': (22, 22, 20)}
TOOLS = {
    # армейская кирка: воронёная сталь, оливковая рукоять с обмоткой
    'diamond_pickaxe': ({'outer': (58, 64, 66), 'light': (184, 192, 194), 'mid': (132, 140, 142),
                         'base': (100, 108, 110), 'dark': (74, 80, 82), 'shadow': (34, 38, 40)}, OLIVE),
    # томагавк: чёрное лезвие, светлая заточка
    'diamond_axe': ({'outer': (40, 44, 46), 'light': (190, 198, 200), 'mid': (82, 88, 90),
                     'base': (66, 70, 72), 'dark': (48, 52, 54), 'shadow': (16, 18, 18)}, OLIVE),
    # сапёрная лопатка: зелёное крашеное полотно, стальная кромка, деревянный черенок
    'diamond_shovel': ({'outer': (46, 60, 32), 'light': (136, 158, 92), 'mid': (108, 132, 72),
                        'base': (86, 108, 58), 'dark': (64, 82, 44), 'shadow': (30, 40, 22)}, WOOD),
}
GRIP_ROWS = 3
STEEL = {'light': (200, 206, 206), 'mid': (160, 168, 168), 'base': (130, 138, 138)}
EDGE_ROWS = 2  # у лопатки верхние ряды полотна — заточенная стальная кромка  # нижние ряды рукояти — обмотка (кроме лопатки)

def recolor(src, head, handle, grip, edge=False):
    im = src.convert('RGBA')
    out = Image.new('RGBA', im.size, (0, 0, 0, 0))
    hrows = sorted({y for y in range(16) for x in range(16)
                    if DIAMOND.get(im.getpixel((x, y))[:3], '').startswith('h_')})
    top = min(y for y in range(16) for x in range(16) if im.getpixel((x, y))[3])
    gripset = set(hrows[-GRIP_ROWS:]) if grip else set()
    for y in range(16):
        for x in range(16):
            p = im.getpixel((x, y))
            if p[3] == 0: continue
            role = DIAMOND.get(p[:3])
            if role is None: raise SystemExit(f'unknown colour {p} at {x},{y}')
            if role.startswith('h_'): c = (CORD if y in gripset else handle)[role]
            elif edge and y < top + EDGE_ROWS and role in STEEL: c = STEEL[role]
            else: c = head[role]
            out.putpixel((x, y), c + (p[3],))
    return out

if __name__ == '__main__':
    jar, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(jar) as z:
        for name, (head, handle) in TOOLS.items():
            src = Image.open(io.BytesIO(z.read(f'assets/minecraft/textures/item/{name}.png')))
            recolor(src, head, handle, name != 'diamond_shovel', name == 'diamond_shovel').save(f'{dst}/{name}.png')
