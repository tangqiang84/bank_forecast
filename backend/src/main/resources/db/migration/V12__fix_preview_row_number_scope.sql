drop index uk_import_preview_row_job_row;

create index idx_import_preview_row_job_row on import_preview_row (tenant_id, import_job_id, row_no);
