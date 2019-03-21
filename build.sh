#!/usr/bin/env bash

set -x
set -e

(
	cd ../java-redis-collections
	./build.sh
	cp bin/java-redis-collections*.jar ../link-checker/lib/
)

rm -rf ./bin/
mkdir bin;

javac -cp 'lib/*' $(find src -name "*.java") $(find test -name "*.java") -d bin
cp src/log4j.properties bin
cd bin && jar -cvf ./java-linkchecker-$(git describe).jar $(find . -name "*.class")
