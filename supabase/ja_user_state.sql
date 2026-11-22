-- JA test-build account sync. Run once in the Supabase SQL Editor.
-- The client uses only the publishable key; every row is scoped by auth.uid().
create table if not exists public.ja_user_state (
    user_id uuid primary key references auth.users(id) on delete cascade,
    state jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now()
);

create table if not exists public.ja_user_access (
    user_id uuid primary key references auth.users(id) on delete cascade,
    approved boolean not null default false,
    created_at timestamptz not null default now(),
    approved_at timestamptz
);

alter table public.ja_user_access enable row level security;
grant select on public.ja_user_access to authenticated;

drop policy if exists "JA users view their own approval status" on public.ja_user_access;
create policy "JA users view their own approval status"
    on public.ja_user_access for select to authenticated
    using ((select auth.uid()) = user_id);

create or replace function public.ja_create_user_access()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.ja_user_access (user_id, approved)
    values (new.id, false)
    on conflict (user_id) do nothing;
    return new;
end;
$$;

drop trigger if exists ja_auth_user_access on auth.users;
create trigger ja_auth_user_access
    after insert on auth.users
    for each row execute procedure public.ja_create_user_access();

-- Existing test-project users also start pending and must be approved manually.
insert into public.ja_user_access (user_id, approved)
select id, false from auth.users
on conflict (user_id) do nothing;

alter table public.ja_user_state enable row level security;

drop policy if exists "JA users read their own state" on public.ja_user_state;
create policy "JA users read their own state"
    on public.ja_user_state for select to authenticated
    using ((select auth.uid()) = user_id and exists (
        select 1 from public.ja_user_access approval
        where approval.user_id = (select auth.uid()) and approval.approved
    ));

drop policy if exists "JA users insert their own state" on public.ja_user_state;
create policy "JA users insert their own state"
    on public.ja_user_state for insert to authenticated
    with check ((select auth.uid()) = user_id and exists (
        select 1 from public.ja_user_access approval
        where approval.user_id = (select auth.uid()) and approval.approved
    ));

drop policy if exists "JA users update their own state" on public.ja_user_state;
create policy "JA users update their own state"
    on public.ja_user_state for update to authenticated
    using ((select auth.uid()) = user_id and exists (
        select 1 from public.ja_user_access approval
        where approval.user_id = (select auth.uid()) and approval.approved
    ))
    with check ((select auth.uid()) = user_id and exists (
        select 1 from public.ja_user_access approval
        where approval.user_id = (select auth.uid()) and approval.approved
    ));

grant select, insert, update on public.ja_user_state to authenticated;
