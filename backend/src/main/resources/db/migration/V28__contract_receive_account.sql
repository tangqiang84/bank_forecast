-- 合同收款账户（P3-7：合同字段补齐）
alter table contract add column receive_account_id bigint null;
create index idx_contract_receive_account on contract (tenant_id, receive_account_id);
