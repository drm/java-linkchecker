# Simple but efficient HTTP link checker

Currently a quite naive but effective implementation of HTML-based link checker. 

## Usage

```text
./build.sh
java -cp 'lib/*:out/' nl.melp.linkchecker.LinkChecker \
    [--follow-local|--follow-from-local]
    --threads=40
    -- 
```
