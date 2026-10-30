-- Connection limits of each file system; a new one gets data/root-options.json's defaults when it is added
UPDATE file_system SET options = json_set(options, '$.max_connections', 4, '$.browsing_reserve', 1,
                                          '$.idle_stream_seconds', 30);
