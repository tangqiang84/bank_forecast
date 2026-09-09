create table forecast_model_version (
  id bigint auto_increment primary key,
  version varchar(64) not null,
  model_name varchar(128) not null,
  status varchar(16) not null default 'active',
  config_json text null,
  metrics_json text null,
  activated_at timestamp null,
  created_at timestamp not null default current_timestamp,
  updated_at timestamp not null default current_timestamp,
  deleted_at timestamp null,
  constraint uk_forecast_model_version unique (version)
);

insert into forecast_model_version (version, model_name, status, config_json, activated_at)
values ('v1', 'moving-average-with-trend', 'active', '{"algorithm":"moving-average-with-trend"}', current_timestamp);

alter table forecast_job add model_version_id bigint null;
create index idx_forecast_job_model_version on forecast_job (model_version_id);
