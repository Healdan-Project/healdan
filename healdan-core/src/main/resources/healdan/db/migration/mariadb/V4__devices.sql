alter table hd_api_token add column hd_device varchar(64);

-- The dates keep milliseconds, as ld_history's ld_date does: a record is told from a later check-out by them
create table hd_hold_device (
	ld_id bigint not null,
	ld_lastmodified datetime(3) not null,
	ld_creation datetime(3) not null,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create sequence hd_hold_device_SEQ start with 100 increment by 50;
