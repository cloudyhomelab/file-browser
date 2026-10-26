-- S3 file systems: an optional endpoint for S3-compatible stores and the access keys, encrypted by SecretBox
ALTER TABLE file_system ADD COLUMN endpoint_url TEXT;
ALTER TABLE file_system ADD COLUMN access_key TEXT;
ALTER TABLE file_system ADD COLUMN secret_key TEXT;
