create table hd_preview_job (
	hd_docid bigint not null,
	hd_fileversion varchar(10) not null,
	hd_attempts int not null,
	hd_nexttry timestamp not null,
	hd_lasterror varchar(4000),
	hd_created timestamp not null,
	primary key (hd_docid, hd_fileversion)
);

create index hd_preview_job_due on hd_preview_job (hd_attempts, hd_nexttry);
