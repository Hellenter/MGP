from draw import *
g=[['.']*16 for _ in range(16)]
W={3:3,4:3,5:3,6:3,7:3,8:3,9:3,10:3,11:2,12:1,13:0}
for y in range(16):
  for x in range(16):
    a=x-y; b=x+y; 
    if a in W and abs(b-15)<=W[a]:
      d=b-15
      if a>=11 or (a==10 and d>-3): ch='E' if d<=0 else 'S'
      elif d==-W[a]: ch='q'
      elif d<0: ch='Q'
      elif d==0: ch='P'
      elif d==W[a]: ch='p'
      else: ch='P'
      if a==3: ch={'q':'Q','Q':'P','P':'p','p':'p'}[ch] if ch in 'qQPp' else ch
      g[y][x]=ch
# neck
for (x,y,ch) in [(8,6,'S'),(8,7,'m'),(7,7,'M'),(7,6,'m')]:
  g[y][x]=ch
for y in range(8,14):
  x=14-y
  if g[y][x]=='.': g[y][x]='B'
  if g[y][x+1]=='.': g[y][x+1]='D'
g[13][1]='d';g[14][1]='d';g[14][0]='d'; g[13][0]='d'
rows=outline([''.join(r) for r in g])
print('\n'.join(rows))
grid_to_img(rows).save('diamond_shovel.png')
