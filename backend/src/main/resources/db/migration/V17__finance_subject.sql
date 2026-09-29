alter table finance_record add subject varchar(128) null;

create index idx_finance_record_tenant_subject on finance_record (tenant_id, subject);
