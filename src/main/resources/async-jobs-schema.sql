-- Esquema de controle da biblioteca async-request-reply (ADR 0004).
--
-- A lib NAO aplica este DDL: aplique-o com a sua ferramenta de migracao
-- (Flyway, Liquibase, script de deploy). Ele e a fonte unica de verdade do
-- esquema e e o mesmo arquivo usado pelos testes de integracao.
--
-- Alvo: Aurora PostgreSQL (indices parciais).

create table if not exists async_jobs (
    id               uuid         primary key,
    type             varchar(64)  not null,
    status           varchar(16)  not null,
    coalescing_key   varchar(128),
    idempotency_key  varchar(255),
    percent_complete smallint,
    error_title      varchar(255),
    error_detail     text,
    created_at       timestamptz  not null,
    last_updated_at  timestamptz  not null
);

-- Idempotencia: um retry da mesma key nao cria segundo job.
create unique index if not exists ux_async_jobs_idem
    on async_jobs (idempotency_key)
    where idempotency_key is not null;

-- Single-flight: no maximo um job ativo por escopo de coalescing. E o que
-- substitui o lock distribuido — a submissao insere e deixa o banco recusar.
create unique index if not exists ux_async_jobs_inflight
    on async_jobs (coalescing_key)
    where status in ('PENDING', 'PROCESSING');

-- Varredura de jobs orfaos (instancia que caiu, worker que nunca reportou).
create index if not exists ix_async_jobs_active
    on async_jobs (last_updated_at)
    where status in ('PENDING', 'PROCESSING');

-- Janela de frescor: ultimo job concluido por escopo, para nao repetir carga
-- quente desnecessariamente.
create index if not exists ix_async_jobs_fresh
    on async_jobs (coalescing_key, last_updated_at desc)
    where status = 'COMPLETED';
