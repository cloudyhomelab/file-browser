-- What admins allow on each file system; a new one gets data/root-options.json copied in when it is added
ALTER TABLE file_system ADD COLUMN options TEXT NOT NULL DEFAULT '{}';

UPDATE file_system SET options = json_object('link_uploads', json('true'), 'extraction', json('true'),
                                             'opening', json('true'));
