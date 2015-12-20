CREATE TABLE member_dois (
    id  INTEGER AUTO_INCREMENT PRIMARY KEY,
    member_id INTEGER NOT NULL,
    doi VARCHAR(512) NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX doi ON member_dois(doi);
CREATE INDEX member_id ON member_dois(member_id);


CREATE TABLE member_domains (
    id  INTEGER AUTO_INCREMENT PRIMARY KEY,
    member_id INTEGER NOT NULL,
    domain VARCHAR(128) NOT NULL,
    ignored BOOLEAN NOT NULL DEFAULT FALSE,
    first_url text NULL,
    last_url text NULL
);

create table member_urls (
  id  INTEGER AUTO_INCREMENT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  url VARCHAR(1024) NOT NULL,
  domain VARCHAR(128) NOT NULL,
  doi VARCHAR(512) NOT NULL 
);

CREATE UNIQUE INDEX member_domains_unique ON member_domains(member_id, domain);
CREATE UNIQUE INDEX member_domains_ignore ON member_domains(domain, ignored);

create table member_prefixes (
  id  INTEGER AUTO_INCREMENT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  member_prefix VARCHAR(128) NOT NULL
);

CREATE UNIQUE INDEX member_prefixes ON member_prefixes(member_id, member_prefix);

-- url and doi should be of unbounded size because we're dealing with arbitrary inputs.
CREATE TABLE url_doi_cache (
  id  INTEGER AUTO_INCREMENT PRIMARY KEY,
  url TEXT,
  doi TEXT,
  success BOOLEAN
);

CREATE UNIQUE INDEX url_doi_cache ON url_doi_cache(url(512), success);
