-- The Trash's record: hd_trash has a row for each delete a person made (one folder or one document), and
-- hd_trash_member a row for each folder or document that delete took, with where it stands (deleted, removed,
-- restored or destroyed) and who moved it there and when.
create table hd_trash (
	hd_id bigint auto_increment primary key,
	hd_tenantid bigint not null,
	hd_kind varchar(10) not null,
	hd_objectid bigint not null,
	hd_name varchar(255) not null,
	hd_docnumber varchar(255),
	hd_fromfolderid bigint,
	hd_frompath varchar(4000),
	hd_matterid bigint,
	hd_folders int not null,
	hd_documents int not null,
	hd_deletedby bigint,
	hd_deletedbyname varchar(255),
	hd_deletedat datetime(3) not null,
	index hd_trash_deleter (hd_tenantid, hd_deletedby)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin;

create table hd_trash_member (
	hd_trashid bigint not null,
	hd_objecttype char(1) not null,
	hd_objectid bigint not null,
	hd_state varchar(10) not null,
	hd_removedby bigint,
	hd_removedbyname varchar(255),
	hd_removedat datetime(3),
	hd_restoredby bigint,
	hd_restoredbyname varchar(255),
	hd_restoredat datetime(3),
	hd_destroyedat datetime(3),
	primary key (hd_trashid, hd_objecttype, hd_objectid),
	index hd_trash_member_object (hd_objecttype, hd_objectid, hd_state),
	index hd_trash_member_removed (hd_state, hd_removedat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin;
