import os,re,sys
src,dst=sys.argv[1],sys.argv[2]
pat=re.compile(r'(?<![\w$"])_(?![\w$"])')
n=0
for root,_d,files in os.walk(src):
    for f in files:
        if not f.endswith('.java'): continue
        path=os.path.join(root,f); t=open(path,encoding='utf-8').read()
        if not re.search(r'[\s(,]_[\s),]',t): continue
        cnt=[0]
        def rep(m):
            cnt[0]+=1; return 'unused%d'%cnt[0]
        # only replace on code lines that look like params/catches
        lines=t.split('\n'); out=[]
        for L in lines:
            if re.search(r'(catch \(\w[\w.]* _\)|[(,]\s*_\s*[,)]|\b_ ->|\(_\)|\w _\)|\w _,)',L) and not L.strip().startswith(('*','//')):
                L=pat.sub(rep,L)
            out.append(L)
        o=os.path.join(dst,os.path.relpath(path,src)); os.makedirs(os.path.dirname(o),exist_ok=True)
        open(o,'w',encoding='utf-8').write('\n'.join(out)); n+=1
print('overlay files',n)
