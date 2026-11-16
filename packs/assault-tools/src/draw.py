from PIL import Image
import sys
P = {
 'K': (24,26,22),      # outline
 # handle wood (shovel)
 'd': (74,50,30), 'D': (110,76,44), 'B': (146,104,62),
 # olive drab
 'o': (52,60,30), 'O': (78,88,44), 'G': (108,120,62), 'g': (134,146,80),
 # cord / grip
 'c': (30,30,30), 'C': (58,58,56),
 # dark steel
 'm': (52,56,58), 'M': (82,88,90), 'S': (122,130,132), 'E': (186,194,196), 'X': (226,232,232),
 # painted green metal (shovel blade)
 'p': (56,72,40), 'P': (80,100,56), 'Q': (108,132,74), 'q': (140,162,98),
}
def grid_to_img(rows):
    assert len(rows)==16, len(rows)
    im = Image.new('RGBA',(16,16),(0,0,0,0))
    for y,r in enumerate(rows):
        assert len(r)==16,(y,r,len(r))
        for x,ch in enumerate(r):
            if ch!='.': im.putpixel((x,y),P[ch]+(255,))
    return im
def outline(rows):
    g=[list(r) for r in rows]
    out=[list(r) for r in rows]
    for y in range(16):
        for x in range(16):
            if g[y][x]=='.':
                for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
                    X,Y=x+dx,y+dy
                    if 0<=X<16 and 0<=Y<16 and g[Y][X] not in '.K':
                        out[y][x]='K';break
    return [''.join(r) for r in out]
