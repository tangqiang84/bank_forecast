alter table match_result add match_group_id varchar(64) null;
alter table match_result add allocation_mode varchar(32) not null default 'single';
alter table match_result add allocated_amount decimal(18,2) not null default 0;

update match_result mr set match_group_id = concat('MR-', mr.id), allocated_amount = (
  select bt.amount from bank_transaction bt where bt.id = mr.bank_transaction_id
) where match_group_id is null;

alter table match_result alter column match_group_id varchar(64) not null;

create table match_result_allocation (
  id bigint auto_increment primary key,
  tenant_id bigint not null,
  match_group_id varchar(64) not null,
  match_result_id bigint not null,
  bank_transaction_id bigint not null,
  contract_receivable_plan_id bigint not null,
  allocated_amount decimal(18,2) not null,
  status varchar(32) not null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null
);

insert into match_result_allocation (
  tenant_id, match_group_id, match_result_id, bank_transaction_id, contract_receivable_plan_id, allocated_amount, status
)
select tenant_id, match_group_id, id, bank_transaction_id, contract_receivable_plan_id, allocated_amount, match_status
from match_result
where contract_receivable_plan_id is not null and deleted_at is null;

create index idx_match_result_tenant_group on match_result (tenant_id, match_group_id);
create index idx_match_allocation_tenant_group on match_result_allocation (tenant_id, match_group_id);
create index idx_match_allocation_tenant_txn on match_result_allocation (tenant_id, bank_transaction_id);
create index idx_match_allocation_tenant_plan on match_result_allocation (tenant_id, contract_receivable_plan_id);
