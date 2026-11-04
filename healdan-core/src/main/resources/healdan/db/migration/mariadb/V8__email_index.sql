create table hd_email (
	hd_docid bigint not null,
	hd_messageid varchar(998) not null,
	hd_messageidhash char(64) not null,
	primary key (hd_docid),
	index hd_email_messageid (hd_messageidhash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
