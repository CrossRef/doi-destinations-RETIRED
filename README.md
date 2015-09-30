# member-domains

Tool to resolve a sample of DOIs to find which domains are used by Crossref members.

## Usage

- Create a mysql database with schema in `etc/schema.sql`.
- Create `config.edn` with `:database-name`, `:database-username`, `:database-host`.

## Ignored domains

Some domains are just too broad (like Google Pages). Maintain a list of these, in MySQL LIKE format in `resources/ignore.txt`. To update the database after a scan, or after updating the file:

    lein run mark-ignored

To update the list of DOIs per publisher:

	lein run dois

To try and resolve the DOIs and extract domains:

	lein run resolve

So a typical cycle is:

    lein run dois
    lein run resolve
    lein run mark-ignored

To dump domain and domain name

	lein run dump 

To dump only domain names:

	lein run dump-domains

To dump a big regular expression of domain names:
  
	lein run regular-expression

## License

Copyright © 2015 Crossref

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
