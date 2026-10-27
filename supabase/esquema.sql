-- =====================================================================
--  Almacén OT · esquema de Supabase (se puede volver a ejecutar sin problema)
--  Pégalo completo en: Supabase > SQL Editor > New query > Run
-- =====================================================================

-- 1) Usuarios y su rol ---------------------------------------------------
--    almacenista = ve y edita todo
--    lector      = jefes de taller y oficinas (solo ven)
--    pendiente   = acaba de iniciar sesión y espera aprobación de un almacenista
--    rechazado   = acceso negado
create table if not exists public.usuarios (
  email         text primary key,
  rol           text not null default 'pendiente',
  nombre        text,
  creado        timestamptz not null default now(),
  ultimo_acceso timestamptz
);

alter table public.usuarios add column if not exists nombre text;
alter table public.usuarios add column if not exists creado timestamptz not null default now();
alter table public.usuarios add column if not exists ultimo_acceso timestamptz;
alter table public.usuarios alter column rol set default 'pendiente';
alter table public.usuarios drop constraint if exists usuarios_rol_check;
alter table public.usuarios add constraint usuarios_rol_check
  check (rol in ('almacenista', 'lector', 'pendiente', 'rechazado'));

insert into public.usuarios (email, rol, nombre) values
  ('shinne0188@gmail.com', 'almacenista', 'Almacenista 1')
on conflict (email) do update set rol = 'almacenista';

-- Más almacenistas (solo se pueden crear desde aquí, no desde la app):
-- insert into public.usuarios (email, rol, nombre) values ('correo@gmail.com', 'almacenista', 'Almacenista 2');

-- 2) Alta automática en el primer inicio de sesión --------------------------
--    Cuando alguien entra por primera vez (con Google o con correo) se guarda su correo y nombre
--    como 'pendiente'. Un almacenista lo aprueba desde la app (botón "Usuarios").
--    Para aprobar a todos sin revisión, cambia 'pendiente' por 'lector' en esta función
--    (no recomendado: cualquier persona con una cuenta de Google podría ver las OT).
create or replace function public.registrar_usuario() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.usuarios (email, rol, nombre)
  values (
    lower(new.email),
    'pendiente',
    coalesce(nullif(new.raw_user_meta_data ->> 'full_name', ''), nullif(new.raw_user_meta_data ->> 'name', ''))
  )
  on conflict (email) do update
    set nombre = coalesce(nullif(public.usuarios.nombre, ''), excluded.nombre);
  return new;
end $$;

drop trigger if exists al_crear_usuario on auth.users;
create trigger al_crear_usuario after insert on auth.users
  for each row execute function public.registrar_usuario();

-- Personas que ya habían iniciado sesión antes de este script
insert into public.usuarios (email, rol, nombre)
select lower(u.email), 'pendiente',
       coalesce(nullif(u.raw_user_meta_data ->> 'full_name', ''), nullif(u.raw_user_meta_data ->> 'name', ''))
from auth.users u
where u.email is not null
on conflict (email) do nothing;

-- 3) Rol de quien está conectado --------------------------------------------
create or replace function public.mi_rol() returns text
language sql stable security definer set search_path = public as $$
  select rol from public.usuarios
  where lower(email) = lower(auth.jwt() ->> 'email')
  limit 1
$$;

-- Registra el acceso (fecha y nombre); si la persona no existe, la da de alta como pendiente
create or replace function public.registrar_acceso(p_nombre text default '') returns void
language plpgsql security definer set search_path = public as $$
declare
  v_email text := lower(auth.jwt() ->> 'email');
begin
  if v_email is null then
    return;
  end if;
  insert into public.usuarios (email, rol, nombre, ultimo_acceso)
  values (v_email, 'pendiente', nullif(p_nombre, ''), now())
  on conflict (email) do update
    set ultimo_acceso = now(),
        nombre = coalesce(nullif(usuarios.nombre, ''), nullif(p_nombre, ''));
end $$;

-- Un almacenista aprueba, rechaza o quita acceso (no puede tocar a otros almacenistas)
create or replace function public.cambiar_rol(p_email text, p_rol text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if public.mi_rol() is distinct from 'almacenista' then
    raise exception 'No autorizado';
  end if;
  if p_rol not in ('lector', 'pendiente', 'rechazado') then
    raise exception 'Rol no permitido';
  end if;
  update public.usuarios
     set rol = p_rol
   where lower(email) = lower(p_email)
     and rol <> 'almacenista';
end $$;

revoke all on function public.mi_rol()                 from public, anon;
revoke all on function public.registrar_acceso(text)   from public, anon;
revoke all on function public.cambiar_rol(text, text)  from public, anon;
grant execute on function public.mi_rol()                to authenticated;
grant execute on function public.registrar_acceso(text)  to authenticated;
grant execute on function public.cambiar_rol(text, text) to authenticated;

-- 4) Órdenes de trabajo (cada OT completa en un solo registro) --------------
create table if not exists public.ordenes (
  id              bigint primary key,
  datos           jsonb not null,
  actualizado     timestamptz not null default now(),
  actualizado_por text
);

create or replace function public.ordenes_sello() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  new.actualizado := now();
  new.actualizado_por := auth.jwt() ->> 'email';
  return new;
end $$;

drop trigger if exists ordenes_sello on public.ordenes;
create trigger ordenes_sello before insert or update on public.ordenes
  for each row execute function public.ordenes_sello();

-- 5) Seguridad: quién puede ver y quién puede editar -------------------------
alter table public.usuarios enable row level security;
alter table public.ordenes  enable row level security;

revoke all on public.usuarios, public.ordenes from anon;
revoke all on public.usuarios from authenticated;
grant select on public.usuarios to authenticated;
grant select, insert, update, delete on public.ordenes to authenticated;

-- Cada persona ve su propia fila (para saber si ya fue aprobada); los almacenistas ven todas
drop policy if exists usuarios_ver_propio on public.usuarios;
drop policy if exists usuarios_ver on public.usuarios;
create policy usuarios_ver on public.usuarios
  for select to authenticated
  using (lower(email) = lower(auth.jwt() ->> 'email') or public.mi_rol() = 'almacenista');

drop policy if exists ordenes_ver on public.ordenes;
create policy ordenes_ver on public.ordenes
  for select to authenticated
  using (public.mi_rol() in ('almacenista', 'lector'));

drop policy if exists ordenes_insertar on public.ordenes;
create policy ordenes_insertar on public.ordenes
  for insert to authenticated
  with check (public.mi_rol() = 'almacenista');

drop policy if exists ordenes_actualizar on public.ordenes;
create policy ordenes_actualizar on public.ordenes
  for update to authenticated
  using (public.mi_rol() = 'almacenista')
  with check (public.mi_rol() = 'almacenista');

drop policy if exists ordenes_borrar on public.ordenes;
create policy ordenes_borrar on public.ordenes
  for delete to authenticated
  using (public.mi_rol() = 'almacenista');

-- 6) Fotos de salida de almacén (espacio privado) ---------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('fotos', 'fotos', false, 5242880, array['image/jpeg'])
on conflict (id) do update
  set public = false, file_size_limit = 5242880, allowed_mime_types = array['image/jpeg'];

drop policy if exists fotos_ver on storage.objects;
create policy fotos_ver on storage.objects
  for select to authenticated
  using (bucket_id = 'fotos' and public.mi_rol() in ('almacenista', 'lector'));

drop policy if exists fotos_subir on storage.objects;
create policy fotos_subir on storage.objects
  for insert to authenticated
  with check (bucket_id = 'fotos' and public.mi_rol() = 'almacenista');

drop policy if exists fotos_cambiar on storage.objects;
create policy fotos_cambiar on storage.objects
  for update to authenticated
  using (bucket_id = 'fotos' and public.mi_rol() = 'almacenista');

drop policy if exists fotos_borrar on storage.objects;
create policy fotos_borrar on storage.objects
  for delete to authenticated
  using (bucket_id = 'fotos' and public.mi_rol() = 'almacenista');
