from draw import *
g=[['.']*16 for _ in range(16)]
head={}
# top arm (fill letters), reflected onto right arm via (x,y)->(15-y,15-x)
top={(4,2):'E',(5,2):'m',(6,2):'M',(7,2):'M',(8,2):'M',(9,2):'M',(10,2):'M',(11,2):'S',
     (5,1):'S',(6,1):'S',(7,1):'E',(8,1):'S',(9,1):'S',(10,1):'S',
     (3,3):'X'}
for (x,y),c in top.items():
  head[(x,y)]=c; head[(15-y,15-x)]=c
center={(10,3):'m',(11,3):'M',(12,3):'S',(12,4):'M',(12,5):'m',(12,2):'S',(13,3):'S',(11,4):'m'}
head.update(center)
# handle
for y in range(5,14):
  x=14-y; g[y][x]='G'; g[y][x+1]='O'
for y in (11,12,13):
  x=14-y; g[y][x]='C'; g[y][x+1]='c'
g[14][1]="c"; g[14][0]="c"; g[4][10]="O"; g[5][9]="c"; g[5][10]="c"
for (x,y),c in head.items(): g[y][x]=c
rows=outline([''.join(r) for r in g])
print('\n'.join(rows))
grid_to_img(rows).save('diamond_pickaxe.png')
