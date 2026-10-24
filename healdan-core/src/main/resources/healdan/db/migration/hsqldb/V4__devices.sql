alter table hd_api_token add column hd_device varchar(64);

create table hd_hold_device (
	ld_id bigint not null,
	ld_lastmodified timestamp not null,
	ld_creation timestamp not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_docid bigint not null,
	hd_device varchar(64),
	hd_tokenid bigint,
	hd_app varchar(100),
	hd_lastsaveid varchar(64),
	primary key (ld_id),
	unique (hd_docid)
);

create sequence hd_hold_device_SEQ start with 100 increment by 50;
