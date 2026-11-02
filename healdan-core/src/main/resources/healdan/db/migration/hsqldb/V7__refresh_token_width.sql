-- M4, ruling R35: a sealed refresh token is "v1:" and the base64 of 28 bytes more than the token, so V6's
-- varchar(4000) held a provider's refresh token of up to about 2,969 characters. MariaDB's is text, 65,535 bytes: a
-- token of up to 49,121 characters, which the code checks (ApiTokens.MAX_REFRESH).
alter table hd_api_token alter column hd_refresh set data type varchar(65535);

-- A revoked token keeps no refresh token, as revoking now clears it: so a copy of the database and the secret key
-- together hold no live one
update hd_api_token set hd_refresh = null where ld_deleted = 1;
