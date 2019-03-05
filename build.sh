#!/usr/bin/env bash

set -x
set -e

rm -rf ./bin/
mkdir bin;

javac -cp 'lib/*' $(find src -name "*.java") $(find test -name "*.java") -d bin
cd bin && jar -cvf ./java-linkchecker-$(git describe).jar $(find . -name "*.class")
