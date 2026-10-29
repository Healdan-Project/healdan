-- The version record (ld_version.ld_id) a document's last save through the API made: a repeat of that save is
-- answered as stored only while that record stands and its file is still the document's. Not the file version alone,
-- which CE gives another file once that version is deleted.
alter table hd_hold_device add column hd_lastsaveversionid bigint;
