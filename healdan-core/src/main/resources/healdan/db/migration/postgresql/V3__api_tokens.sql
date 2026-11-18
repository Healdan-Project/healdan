create table hd_api_token (
	ld_id bigint not null,
	ld_lastmodified timestamp not null,
	ld_creation timestamp not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_userid bigint not null,
	hd_hash varchar(64) not null,
	hd_name varchar(255) not null,
	hd_lastused timestamp not null,
	hd_expires timestamp not null,
	hd_sid varchar(255),
	primary key (ld_id),
	unique (hd_hash)
);

create index hd_api_token_user on hd_api_token (hd_userid);

create sequence hd_api_token_SEQ start with 100 increment by 50;
