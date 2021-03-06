# Simple but efficient HTTP link checker

## Project goal

To be able to run a production grade link checker on your own website 

* Avoiding dead links to other websites;
* Checking the integrity of your own website's pages.

The aim is to be efficient, highly performant, resumable and distributable.

The project was used successfully on websites with up to 300K links. 

## Usage

```text
./build.sh
java -cp 'lib/*:bin/*.jar' nl.melp.linkchecker.LinkChecker
    [--redis-host=HOST]
    [--redis-port=PORT]
    [--threads=N]
    [--reset|--resume|--recheck]
    [--report|--report-all]
    [--follow-local|--follow-from-local|--no-follow]
    [--recheck-only-errors|--no-recheck]
    [--ignore=PATTERN1[,PATTERN2...] [--ignore=PATTERN3...]]
    [--include=PATTERN1[,PATTERN2...] [--include=PATTERN3...]]
    [--ignore-ssl-errors]
    http://localhost/
    https://localhost/
```

### Available flags and options:

| Flag | Description |
| ------------- | ------------- |
| `--threads=N`  | Configure number of threads to use. There will be running 1 master thread, 1 logger thread and N worker threads. |
| `--redis-host=HOST` | Configure HOST as the Redis host. |
| `--redis-port=PORT` | Configure PORT as the Redis port |
| `--follow-local` | Only local links to that local* domain are followed |
| `--follow-from-local` | Only follow links that are mentioned on the local* domain. This means that the link checker only spans over multiple hosts *once*. |
| `--no-follow` | No links are followed. This is typically useful in combination with the `--recheck` flag |
| `--recheck` | Reset the status for each of the previously failed URLs, and recheck the pages they are mentioned on. |
| `--no-recheck` | Don't do recheck, even if url's are marked as "processing". |
| `--reset` | Start with a clean slate |
| `--resume` | Resume a previously stopped session. |
| `--report` | When done, write a report to stdout and to reporting keys in Redis. |
| `--report-all` | Also report working links. By default, only error statuses are reported |

*) The start URLs passed in the command line will be considered "local
domains". This means that with the flags `--follow-from-local`, pages
read from domains that are part of the arguments list are considered
"local" pages and every link mentioned on that page will be followed.
Similarly, if the `--follow-local` flag is passed, only links on the
same domain as the domains mentioned in these start urls are followed.

Note that this way you can actually allow multiple domains to be checked,
by specifying multiple start urls on different domains. 

## Resuming state
All status data is stored in Maps and Sets which are persisted in
[Redis](https://www.redis.org). This means that you can resume a previously
started session with different options. If you were crawling a website with the
`--follow-local` or `--follow-from-local` flags, you must pass the start url as
a parameter, so that will be considered a local domain. Note that this doesn't
mean that the start URL gets visited again, because the status for that URL is
already in memory and therefore will not be checked again.

## Running redis
You can easily start Redis using [the official Docker
repo](https://hub.docker.com/_/redis) or install it on your host system. You
won't need further configuration.

## Issues?
Please report them at github.com/drm/java-linkchecker

# Copyright
(c) 2019-2020 Gerard van Helden
