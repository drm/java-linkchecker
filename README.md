# Simple but efficient HTTP link checker

Currently a quite naive but effective implementation of HTML-based link checker. 

## Usage

```text
./build.sh
java -cp 'lib/*:bin/*.jar' nl.melp.linkchecker.LinkChecker \
    [--follow-local|--follow-from-local|--no-follow]
    --threads=40
    http://localhost/
    https://localhost/
```

## Issues?
Please report them at github.com/drm/java-linkchecker

# Copyright
(c) 2019 Gerard van Helden
