./build.sh

java \
    -Xmx512M \
    -Xms512M \
    -cp 'lib/*:bin' \
    nl.melp.linkchecker.LinkChecker \
    --follow-from-local \
    --threads=40 \
    --ignore='/.*.pdf' \
    $@ \
