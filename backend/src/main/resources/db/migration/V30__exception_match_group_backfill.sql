-- 回款处理工作台（P4-16）：存量 suggested 匹配组配对回填 match_group_id
-- 前置条件：V29（exception_case.match_group_id + uk_exception_tenant_match_group）已执行
-- 影响范围：match_status='suggested' 的存量匹配组与 partial_receipt 存量工单
-- 执行前 dry-run 报告与执行后校验：scripts/p4_backfill_dry_run.sql

-- 1) partial_receipt 工单回填：按计划取该计划最新 suggested 组写入承载关联；
--    组已被其他工单占用（如拆分跨计划）则跳过，由第 2 步补建 match_review
update exception_case e
join (
  select m.exception_id, m.match_group_id from (
    select e2.id as exception_id, mg.match_group_id as match_group_id
    from exception_case e2
    join (
      select tenant_id, contract_receivable_plan_id, max(id) as latest_id
      from match_result
      where match_status = 'suggested' and deleted_at is null
      group by tenant_id, contract_receivable_plan_id
    ) latest on latest.tenant_id = e2.tenant_id and latest.contract_receivable_plan_id = e2.source_id
    join match_result mg on mg.id = latest.latest_id
    where e2.exception_type = 'partial_receipt' and e2.source_type = 'contract_receivable_plan'
      and e2.deleted_at is null and e2.match_group_id is null
      and not exists (
        select 1 from exception_case x
        where x.tenant_id = e2.tenant_id and x.match_group_id = mg.match_group_id and x.deleted_at is null
      )
  ) m
) mapped on mapped.exception_id = e.id
set e.match_group_id = mapped.match_group_id, e.updated_at = now();

-- 2) 无承载工单的孤儿 suggested 组：补建 match_review 工单（含同计划被跳过承载的旧组）
insert into exception_case (tenant_id, exception_no, exception_type, source_type, source_id, title, description, status, severity, due_date, match_group_id, created_at, updated_at)
select mr.tenant_id, concat('EXC-MIG-', mr.match_group_id), 'match_review', 'match_result', min(mr.id),
  concat('待核销：', coalesce(nullif(c.contract_name, ''), '存量匹配组')),
  concat('存量迁移补建：建议金额 ', sum(mr.allocated_amount), '，匹配依据：', substring_index(group_concat(mr.match_reason order by mr.id), ',', 1)),
  'new', 'medium', min(p.due_date), mr.match_group_id, now(), now()
from match_result mr
left join contract c on c.id = mr.contract_id
left join contract_receivable_plan p on p.id = mr.contract_receivable_plan_id
where mr.match_status = 'suggested' and mr.deleted_at is null
  and not exists (
    select 1 from exception_case e
    where e.tenant_id = mr.tenant_id and e.match_group_id = mr.match_group_id and e.deleted_at is null
  )
group by mr.tenant_id, mr.match_group_id, c.contract_name;
