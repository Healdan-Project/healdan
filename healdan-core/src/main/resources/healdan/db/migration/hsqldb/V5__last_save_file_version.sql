-- The file version a document's last save through the API stored, as ld_document.ld_fileversion holds it: a
-- repeat of that save is answered as stored only while the document's file is still that one
alter table hd_hold_device add column hd_lastsavefileversion varchar(10);
