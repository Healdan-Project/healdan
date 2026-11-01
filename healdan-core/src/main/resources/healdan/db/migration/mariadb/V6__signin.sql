-- M4: links between accounts and identities at sign-in providers (spec section 7). A row stays when unlinked, as a
-- record; hd_linkkey and hd_linkuser are set only while linked, so the unique indexes allow one link in use per
-- identity and per user while unlinked rows repeat.
create table hd_signin_identity (
	ld_id bigint not null,
	ld_lastmodified datetime not null,
	ld_creation datetime not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_provider varchar(32) not null,
	hd_identity varchar(255) not null,
	hd_userid bigint not null,
	hd_linked datetime not null,
	hd_how varchar(10) not null,
	hd_lastsignin datetime,
	hd_unlinked datetime,
	hd_unlinkedby bigint,
	hd_linkkey varchar(300),
	hd_linkuser bigint,
	primary key (ld_id),
	unique (hd_linkkey),
	unique (hd_linkuser)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_signin_identity_provider on hd_signin_identity (hd_provider);
create sequence hd_signin_identity_SEQ start with 100 increment by 50;

-- M4: each provider's group map (spec section 8), by the Healdan group's id
create table hd_signin_group (
	ld_id bigint not null,
	ld_lastmodified datetime not null,
	ld_creation datetime not null,
	ld_recordversion bigint not null,
	ld_deleted int not null,
	ld_tenantid bigint not null,
	hd_provider varchar(32) not null,
	hd_value varchar(255) not null,
	hd_groupid bigint not null,
	primary key (ld_id),
	unique (hd_provider, hd_value, hd_groupid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_signin_group_SEQ start with 100 increment by 50;

-- M4: a device token from a provider sign-in, its sealed refresh token, and its last re-check (spec section 9)
alter table hd_api_token add column hd_provider varchar(32);
alter table hd_api_token add column hd_refresh varchar(4000);
alter table hd_api_token add column hd_checked datetime;
