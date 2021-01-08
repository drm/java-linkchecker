#!/usr/bin/env bash
PORT=8000

ROOT="$(cd $(dirname "$0") && pwd)"
#set -e -u -x
set -e -u

export SERVER_PID=""

_kill() {
	echo "SERVER_PID=$SERVER_PID";
	local server_pid="$SERVER_PID"
	while [ "$server_pid" != "" ]; do
		echo "Killing $server_pid"
		local server_pid="$(ps -o pid= "$server_pid")"
		if [ "$server_pid" != "" ]; then
			echo -n "."
			kill $server_pid;
			sleep .5
		fi
	done
	echo ""
}

_serve() {
	cd "$1";
	_kill
	dir="$1"
	python -m SimpleHTTPServer &
	SERVER_PID="$!"
	while ! nc -z 127.0.0.1 "$PORT"; do
		echo -n "."; sleep .5;
	done
	echo "$SERVER_PID running in $1"
	cd $ROOT
}

_serve resources/sample-2/with-broken-1
./run.sh --reset "http://localhost:$PORT/" --report --follow-from-local
_kill

_serve resources/sample-2/with-broken-2
./run.sh --reset "http://localhost:$PORT/" --report --follow-from-local
_kill

_serve resources/sample-2/all-fixed
./run.sh --recheck --report --report-all --follow-from-local
_kill

