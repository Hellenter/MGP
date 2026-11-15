#!/bin/bash
# usage: build-jar.sh <project dir> <version> <out jar>
set -e
SRC=$1; VER=$2; OUT=$3
P=/tmp/claude-0/paper/paper-api/src
# Paper-исходники с "_" (preview в Java 21) — делаем копию без него
OV=/tmp/claude-0/overlay; rm -rf $OV; mkdir -p $OV
python3 /tmp/claude-0/overlay.py $P/main/java $OV
W=/tmp/claude-0/jarbuild; rm -rf $W; mkdir -p $W/classes
javac --release 21 -proc:none -implicit:none -nowarn -encoding UTF-8 -d $W/classes \
  -sourcepath $OV:$P/main/java:$P/generated/java:/tmp/claude-0/stubs -cp "/tmp/claude-0/deps/lib/*" $(find $SRC/src/main/java -name '*.java')
cp -r $SRC/src/main/resources/. $W/classes/
sed -i "s/\${version}/$VER/" $W/classes/plugin.yml
rm -f $OUT; (cd $W/classes && jar --create --file $OUT .)
