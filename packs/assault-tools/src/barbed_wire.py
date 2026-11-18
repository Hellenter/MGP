"""Колючая проволока вместо паутины: блок и предмет используют одну текстуру textures/block/cobweb.png.
Рисуется по пикселям, ванильный jar не нужен. Запуск: python3 barbed_wire.py <папка textures/block>."""
import sys, os
from PIL import Image

DARK, MID, LIGHT, RUST = (58, 60, 60, 255), (112, 116, 116, 255), (168, 172, 170, 255), (110, 70, 42, 255)

def strand(px, y0, phase):
    """Две свитые проволоки: пересекаются каждые 4 пикселя."""
    for x in range(16):
        t = (x + phase) % 4
        a, b = {0: (0, 1), 1: (0, 1), 2: (1, 0), 3: (1, 0)}[t]
        px[(x, y0 + a)] = LIGHT if t in (0, 2) else MID
        px[(x, y0 + b)] = MID if t in (0, 2) else DARK

def barb(px, x, y):
    """Шип: косой крест из четырёх концов вокруг скрутки."""
    for dx, dy in ((-1, -1), (1, -1), (-1, 2), (1, 2)):
        if 0 <= x + dx < 16 and 0 <= y + dy < 16: px[(x + dx, y + dy)] = DARK
    for dx, dy in ((-2, -2), (2, 3)):
        if 0 <= x + dx < 16 and 0 <= y + dy < 16: px[(x + dx, y + dy)] = MID

def main(out):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = im.load()
    strand(px, 2, 0)
    strand(px, 7, 2)
    strand(px, 12, 1)
    for x, y in ((3, 2), (11, 2), (7, 7), (14, 7), (2, 12), (10, 12)):
        barb(px, x, y)
    # лёгкая ржавчина на проволоке
    for x, y in ((5, 3), (12, 8), (8, 13), (1, 7)):
        if px[(x, y)][3]: px[(x, y)] = RUST
    os.makedirs(out, exist_ok=True)
    im.save(os.path.join(out, 'cobweb.png'))

if __name__ == '__main__':
    main(sys.argv[1])
