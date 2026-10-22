create table hd_preview_job (
	hd_docid bigint not null,
	hd_fileversion varchar(10) not null,
	hd_attempts int not null,
	hd_nexttry datetime not null,
	hd_lasterror varchar(4000),
	hd_created datetime not null,
	primary key (hd_docid, hd_fileversion),
	index hd_preview_job_due (hd_attempts, hd_nexttry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
