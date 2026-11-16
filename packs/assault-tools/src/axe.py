from draw import *
g=[['.']*16 for _ in range(16)]
for y in range(3,14):
  x=14-y; g[y][x]='G'; g[y][x+1]='O'
for y in (11,12,13):
  x=14-y; g[y][x]='C'; g[y][x+1]='c'
g[14][1]='c'; g[14][0]='c'
for y in range(16):
  for x in range(16):
    a=x-y; d=x+y-15
    ch=None
    if -9<=d<=1:
      e=max(0,-d-2)
      lo=5-e*0.95; hi=9.2
      if lo-0.01<=a<=hi:
        if d<=-8: ch='X' if a>lo+1.5 else 'E'
        elif d==-7: ch='E' if a<lo+1 else 'S'
        elif a>=hi-0.7 : ch='S' if d<-3 else 'M'
        elif a<lo+1 and d<-3: ch='m'
        else: ch='M' if d<-3 else 'm'
    elif 2<=d<=4:
      c=7; w=1.2-(d-2)*0.5
      if abs(a-c)<=w: ch='S' if d==4 else 'm'
    if ch: g[y][x]=ch
g[7][14]='M'
rows=outline([''.join(r) for r in g])
print('\n'.join(rows))
grid_to_img(rows).save('diamond_axe.png')
