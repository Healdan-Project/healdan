create table hd_api_token (
	ld_id bigint not null,
	ld_lastmodified datetime not null,
	ld_creation datetime not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_userid bigint not null,
	hd_hash varchar(64) not null,
	hd_name varchar(255) not null,
	hd_lastused datetime not null,
	hd_expires datetime not null,
	hd_sid varchar(255),
	primary key (ld_id),
	unique (hd_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index hd_api_token_user on hd_api_token (hd_userid);

create sequence hd_api_token_SEQ start with 100 increment by 50;
