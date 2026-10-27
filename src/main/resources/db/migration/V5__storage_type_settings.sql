-- Type-specific settings move into JSON, so a new storage type needs no columns of its own:
-- settings holds plain values, secrets the values SecretBox sealed (bound to "<id>/<key>", as before).
ALTER TABLE file_system ADD COLUMN settings TEXT NOT NULL DEFAULT '{}';
ALTER TABLE file_system ADD COLUMN secrets TEXT NOT NULL DEFAULT '{}';

-- json_patch leaves out keys whose value is null
UPDATE file_system
SET settings = json_patch('{}', json_object('region', region, 'endpoint_url', endpoint_url)),
    secrets  = json_patch('{}', json_object('access_key', access_key, 'secret_key', secret_key))
WHERE type = 'S3';

UPDATE file_system SET type = lower(type);

ALTER TABLE file_system DROP COLUMN region;
ALTER TABLE file_system DROP COLUMN endpoint_url;
ALTER TABLE file_system DROP COLUMN access_key;
ALTER TABLE file_system DROP COLUMN secret_key;
