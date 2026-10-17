create table hd_setting (
	name varchar(255) not null,
	value varchar(4000),
	primary key (name)
);

create table hd_counter (
	name varchar(64) not null,
	next_value bigint not null,
	primary key (name)
);

create table hd_client (
	ld_id bigint not null,
	ld_lastmodified timestamp not null,
	ld_creation timestamp not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_number bigint not null,
	hd_name varchar(255) not null,
	primary key (ld_id),
	unique (hd_number)
);

create table hd_matter (
	ld_id bigint not null,
	ld_lastmodified timestamp not null,
	ld_creation timestamp not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_clientid bigint not null,
	hd_number bigint not null,
	hd_name varchar(255) not null,
	hd_description varchar(4000),
	hd_folderid bigint not null,
	hd_ownerid bigint not null,
	hd_templateid bigint,
	primary key (ld_id),
	unique (hd_folderid),
	unique (hd_clientid, hd_number),
	foreign key (hd_clientid) references hd_client (ld_id)
);

create table hd_searchfolder (
	hd_folderid bigint not null,
	hd_days int not null,
	hd_onlymine int not null,
	hd_kind varchar(16) not null,
	hd_docclass varchar(255),
	hd_docsubclass varchar(255),
	primary key (hd_folderid)
);

create table hd_local_value (
	hd_objecttype char(1) not null,
	hd_objectid bigint not null,
	hd_attribute varchar(255) not null,
	primary key (hd_objecttype, hd_objectid, hd_attribute)
);

create sequence hd_client_SEQ start with 100 increment by 50;
create sequence hd_matter_SEQ start with 100 increment by 50;

insert into hd_counter (name, next_value) values ('docnumber', 1);
