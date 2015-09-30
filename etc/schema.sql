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
    ignored BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX member_domains_unique ON member_domains(member_id, domain);
CREATE UNIQUE INDEX member_domains_ignore ON member_domains(domain, ignore);

