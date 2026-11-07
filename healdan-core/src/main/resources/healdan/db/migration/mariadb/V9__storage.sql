-- S3 storage backends (spec section 5): each backend, each Store's backend, one row a file, and held deletes.
create table hd_storage_backend (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_name varchar(100) not null,
	hd_kind varchar(10) not null,
	hd_path varchar(1000),
	hd_endpoint varchar(1000), hd_region varchar(100), hd_bucket varchar(255), hd_prefix varchar(255),
	hd_pathstyle int not null, hd_accesskey varchar(255), hd_secret varchar(4000),
	hd_encrypted int not null, hd_keyconfirmed int not null,
	primary key (ld_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_backend_SEQ start with 100 increment by 50;
insert into hd_storage_backend (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted, ld_tenantid,
	hd_name, hd_kind, hd_pathstyle, hd_encrypted, hd_keyconfirmed)
	values (1, current_timestamp, current_timestamp, 1, 0, 1, 'Original', 'local', 0, 0, 0);

create table hd_storage_store (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_storeid bigint not null, hd_backendid bigint not null,
	primary key (ld_id), unique (hd_storeid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_store_SEQ start with 100 increment by 50;

-- hd_backendid 0 is staging
create table hd_storage_file (
	hd_docid bigint not null, hd_resource varchar(255) not null,
	hd_backendid bigint not null, hd_storeid bigint, hd_size bigint not null, hd_write bigint not null,
	hd_stored datetime not null, hd_waiting datetime,
	hd_attempts int not null, hd_lasterror varchar(4000), hd_stuck int not null, hd_nexttry datetime,
	primary key (hd_docid, hd_resource)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_storage_file_store on hd_storage_file (hd_storeid, hd_backendid);
create index hd_storage_file_due on hd_storage_file (hd_stuck, hd_nexttry);
create index hd_storage_file_waiting on hd_storage_file (hd_waiting);

-- hd_location: the object key, or the path under the backend's .held/ folder. Holding a location again moves its
-- purge date on, so a location is held once at a time. 700 characters keep the key under InnoDB's 3,072 bytes in
-- utf8mb4, and hold the longest location: a 255-character prefix, CE's folders for a doc id and a resource.
create table hd_storage_held (
	hd_backendid bigint not null, hd_location varchar(700) not null,
	hd_docid bigint not null, hd_resource varchar(255) not null,
	hd_held datetime not null, hd_purge datetime not null,
	primary key (hd_backendid, hd_location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_storage_held_purge on hd_storage_held (hd_purge);

-- Each showing and entering of a backend's recovery key (question 8), kept for good.
create table hd_storage_event (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_backendid bigint not null, hd_event varchar(20) not null, hd_date datetime not null,
	hd_userid bigint not null, hd_username varchar(255) not null,
	primary key (ld_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_event_SEQ start with 100 increment by 50;
create index hd_storage_event_backend on hd_storage_event (hd_backendid, hd_date);
