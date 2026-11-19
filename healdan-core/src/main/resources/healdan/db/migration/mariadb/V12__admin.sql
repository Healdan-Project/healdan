-- The administration record (Admin app spec, 9.3): a row for each administrative change, with when, who (id and
-- name), from where, the Admin app's area, the action, what it acted on (kind, id and name), and a JSON detail of the
-- fields it changed with their old and new values. A changed secret is recorded as changed, with no value.
create table hd_admin_event (
	hd_id bigint auto_increment primary key,
	hd_tenantid bigint not null,
	hd_date datetime(3) not null,
	hd_userid bigint not null,
	hd_username varchar(255) not null,
	hd_address varchar(255),
	hd_area varchar(20) not null,
	hd_action varchar(60) not null,
	hd_targetkind varchar(20) not null,
	hd_targetid bigint,
	hd_targetname varchar(255),
	hd_detail text,
	index hd_admin_event_target (hd_targetkind, hd_targetid),
	index hd_admin_event_user (hd_userid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin;

-- The errors the server logged (Admin app spec, 9.4): a row for each fault, by its fingerprint, with its latest
-- message, stack and documents named, how many times it was seen, when first and last, and whether and by whom it was
-- resolved.
create table hd_error (
	hd_id bigint auto_increment primary key,
	hd_fingerprint char(64) not null,
	hd_level varchar(10) not null,
	hd_area varchar(30) not null,
	hd_logger varchar(255) not null,
	hd_message text not null,
	hd_stack text,
	hd_documents varchar(1000),
	hd_total bigint not null,
	hd_firstseen datetime(3) not null,
	hd_lastseen datetime(3) not null,
	hd_resolved int not null,
	hd_resolvedby varchar(255),
	hd_resolvedat datetime(3),
	unique index hd_error_fingerprint (hd_fingerprint),
	index hd_error_lastseen (hd_lastseen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin;

-- How many times each fault was seen in each hour, for the Errors screen's time windows
create table hd_error_hour (
	hd_errorid bigint not null,
	hd_hour datetime not null,
	hd_count int not null,
	primary key (hd_errorid, hd_hour)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin;
