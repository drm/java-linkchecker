#!/usr/bin/env bash

ROOT=$(cd $(dirname $0) && pwd)

fetch() {
	fetch_maven_deps() {
		local dir=$(mktemp -d);

		(
			cd "$dir";
			cat > pom.xml <<-EOF
				<project>
					<modelVersion>4.0.0</modelVersion>
					<groupId>temp</groupId>
					<artifactId>temp</artifactId>
					<version>master</version>
					<repositories>
						<repository>
							<id>jitpack.io</id>
							<url>https://jitpack.io</url>
						</repository>
					</repositories>
					<dependencies>
			EOF

			for package in "$@"; do
				echo "$package" | \
					sed -E 's!([^:]+):([^:]+):([^:]+)!<dependency><groupId>\1</groupId><artifactId>\2</artifactId><version>\3</version></dependency>!g' \
					>> pom.xml
			done;

			cat >> pom.xml <<-EOF
					</dependencies>
				</project>
			EOF

			mvn dependency:copy-dependencies
			mv target/dependency/* "$ROOT";
		)
		rm -rf "$dir";
	}

	fetch_maven_deps \
		'org.apache.httpcomponents:httpclient:4.5.12' \
		'junit:junit:4.12' \
		'org.slf4j:slf4j-log4j12:1.7.7' \
		'org.jsoup:jsoup:1.11.3'

	curl -sL https://github.com/drm/java-redis-client/releases/download/v2.0.2/java-redis-client-v2.0.2--javac-11.0.2.jar -o ./java-redis-client-v2.0.2.jar
	curl -sL https://github.com/drm/java-redis-collections/releases/download/v1.0.2/java-redis-collections-v1.0.2--javac-11.0.7.jar -o ./java-redis-collections-v1.0.2.jar
}

clean() {
	rm -f *.jar;
}

if [[ "$1" == "" ]]; then
	echo "Usage: ${0} [clean] fetch"
	exit 1
fi

set -x
set -e


for a in $@; do
	$a;
done
