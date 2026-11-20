-- LogicalDOC's generic schema script gives these eight columns a varchar that PostgreSQL enforces, where its MariaDB
-- script has mediumtext. They are widened to text, and nothing else of LogicalDOC's tables is ever changed
-- (docs/superpowers/specs/2026-11-18-postgresql-design.md, 6.4).
alter table ld_user alter column ld_avatar type text;
alter table ld_systemmessage alter column ld_messagetext type text;
alter table ld_filler alter column ld_automation type text;
alter table ld_filler alter column ld_automationbefore type text;
alter table ld_folder alter column ld_tile type text;
alter table ld_menu alter column ld_automation type text;
alter table ld_generic alter column ld_text1 type text;
alter table ld_legal alter column ld_content type text;
