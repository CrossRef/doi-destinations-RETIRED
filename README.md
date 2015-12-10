# DOI Destinations

Tool to resolve a sample of DOIs to find which domains are used by Crossref members. Runs at http://destinations.labs.crossref.org . 

## Installation

- Create a mysql database with schema in `etc/schema.sql`.
- Create `config.edn` with `:database-name`, `:database-username`, `:database-host`.

## To run

Some tasks can be run on the command line but the main mode of operation is as a server.

    lein run server

To update the list of DOI and member IDs per publisher:

  lein run update

To try and resolve the DOIs and extract domains. This will run until all DOIs are resolved (i.e. pretty much indefinitely).

  lein run resolve-all

So a typical cycle is:

## Ignored domains

Some domains are just too broad (like Google Pages). Maintain a list of these, in MySQL LIKE format in `resources/ignore.txt`. To update the database after a scan, or after updating the file:

    lein run mark-ignored

## License

Copyright © 2015 Crossref

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.