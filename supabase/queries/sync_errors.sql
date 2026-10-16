-- =============================================================================
-- Sync error reports: run in the Supabase SQL editor after a release, and whenever sync
-- seems off. The app sends unexpected sync failures (see 20261018000001_sync_error_reports.sql).
-- =============================================================================

-- What failed in the last 7 days, most common first, per app version.
select app_version, kind, left(detail, 200) as detail,
       count(*) as reports, count(distinct user_id) as users, max(created_at) as last_seen
from public.sync_error_reports
where created_at > now() - interval '7 days'
group by app_version, kind, left(detail, 200)
order by reports desc
limit 50;

-- Reports per day and version: a jump after a release points at that version.
select date_trunc('day', created_at) as day, app_version, count(*) as reports, count(distinct user_id) as users
from public.sync_error_reports
where created_at > now() - interval '30 days'
group by 1, 2
order by 1 desc, 2 desc;

-- Reports older than 90 days are deleted every night (cron job purge-sync-error-reports). Its last runs:
select status, return_message, start_time from cron.job_run_details
where jobid = (select jobid from cron.job where jobname = 'purge-sync-error-reports')
order by start_time desc limit 5;
