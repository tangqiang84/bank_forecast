-- P4-16 存量迁移 dry-run 报告与校验脚本
-- 用法（执行 V30 前）：mysql -h 127.0.0.1 -P 3307 -u bank_forecast -p bank_forecast < scripts/p4_backfill_dry_run.sql
-- V30 由 Flyway 在后端启动时执行；执行后重跑本脚本核对校验段。

-- ============ 执行前报告 ============

-- 将回填的 partial_receipt 工单数（有 suggested 组可配对、当前无 match_group_id）
select 'paired_partial_receipt' as item, count(*) as count
from exception_case e
where e.exception_type = 'partial_receipt' and e.source_type = 'contract_receivable_plan'
  and e.deleted_at is null and e.match_group_id is null
  and exists (
    select 1 from match_result mr
    where mr.tenant_id = e.tenant_id and mr.contract_receivable_plan_id = e.source_id
      and mr.match_status = 'suggested' and mr.deleted_at is null
  );

-- 将补建 match_review 的孤儿 suggested 组数（无任何工单承载）
select 'orphan_suggested_groups' as item, count(*) as count from (
  select mr.tenant_id, mr.match_group_id
  from match_result mr
  where mr.match_status = 'suggested' and mr.deleted_at is null
    and not exists (
      select 1 from exception_case e
      where e.tenant_id = mr.tenant_id and e.match_group_id = mr.match_group_id and e.deleted_at is null
    )
  group by mr.tenant_id, mr.match_group_id
) t;

-- 已有承载关联的工单数（迁移不涉及）
select 'already_carried' as item, count(*) as count
from exception_case where match_group_id is not null and deleted_at is null;

-- ============ 执行后校验（V30 执行后应全部为零） ============

-- 孤儿 suggested 组（应 = 0）
select 'check_orphan_groups_should_be_0' as item, count(*) as count from (
  select mr.tenant_id, mr.match_group_id
  from match_result mr
  where mr.match_status = 'suggested' and mr.deleted_at is null
    and not exists (
      select 1 from exception_case e
      where e.tenant_id = mr.tenant_id and e.match_group_id = mr.match_group_id and e.deleted_at is null
    )
  group by mr.tenant_id, mr.match_group_id
) t;

-- 重复 match_group_id 占用（应 = 0；唯一索引兜底，正常不会触发）
select 'check_duplicate_carriers_should_be_0' as item, count(*) as count from (
  select tenant_id, match_group_id
  from exception_case
  where match_group_id is not null and deleted_at is null
  group by tenant_id, match_group_id
  having count(*) > 1
) t;
