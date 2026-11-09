-- The Storage tab counts a backend's files, and checks whether any are left on it, by its id alone (StoredFiles.usageOn
-- and countOn): hd_storage_file_store leads with the Store.
create index hd_storage_file_backend on hd_storage_file (hd_backendid);
