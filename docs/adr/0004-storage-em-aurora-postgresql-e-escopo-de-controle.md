# ADR 0004 — Storage em Aurora PostgreSQL e redução ao plano de controle

- **Status:** Aceito (em implementação)
- **Data:** 2026-07
- **Substitui:** parte do ADR 0001 (filtragem adiada) e o storage do ADR 0002 (pub/sub)
- **Contexto:** o caso real é aquecimento de dados — a rotina lê a base atual e a mantém quente

## Contexto

O desenho original guardava tudo em Valkey/Redis: estado do job, idempotência,
guard de single-flight, índice de ativos, locks de transição, pub/sub de eventos
e **o resultado** materializado como LIST paginável.

No uso real, a rotina do consumidor atualiza dados que já vivem no Aurora. Isso
torna o resultado em Redis uma **cópia**: escrita duas vezes, serializada em JSON,
com tipos perdidos, presa ao TTL e divergindo da verdade no banco. Pior, o job era
marcado `COMPLETED` num sistema enquanto os dados eram comitados em outro — sem
transação comum, um crash entre os dois passos deixa trabalho concluído com job
marcado como falho pela varredura.

Some-se a isso que os dados **não** esfriam a cada instante: se a carga acabou de
rodar, repeti-la dentro da janela em que o dado segue quente é desperdício puro.

## Decisão

**1. Storage único: Aurora PostgreSQL.** Uma tabela de controle (`async_jobs`),
sem tabela de resultado. O Redis sai por completo.

**2. Escopo reduzido ao plano de controle.** A biblioteca controla execução —
status, progresso, falha, idempotência, coalescing, frescor e recuperação. Ela
**não** serve dados: a leitura fica no endpoint de domínio do consumidor, com o
SQL dele e filtros livres. Consequência direta: `JobResultStorePortOut` e
`GET /jobs/{id}/result` deixam de existir, e o `303` aponta para o recurso de
domínio.

**3. Concorrência pelo banco, não por lock.** O que exigia `RLock` do Redisson
vira constraint e statement condicional:

- single-flight: índice único parcial em `coalescing_key` onde o status é ativo.
  A submissão passa de "checar e então criar" para `INSERT ... ON CONFLICT DO
  NOTHING RETURNING id` — se não voltou linha, existe job ativo e devolvemos o
  dele. Sem lock, correto entre instâncias e no mesmo milissegundo;
- transições: `UPDATE ... WHERE id = ? AND status IN (...) RETURNING
  last_updated_at`, que devolve o instante persistido quando aplicou e nada
  quando não — exatamente a assinatura `Optional<Instant>` já adotada;
- idempotência: índice único parcial em `idempotency_key`.

Com isso o port `SingleFlightPortOut` é absorvido pelo `create` do repositório e
deixa de existir.

**4. Janela de frescor (nova capacidade, opt-in).** Distinta do single-flight:
aquela cobre "há job **em andamento**", esta cobre "houve job **concluído**
recentemente". Não pode ser constraint (predicado de índice parcial precisa ser
imutável, não pode chamar `now()`), então é consulta pelo último `COMPLETED`
dentro da janela. As duas se compõem: a consulta é caminho rápido consultivo e o
índice único é a rede de segurança contra a corrida. Só `COMPLETED` conta — um
refresh que falhou não suprime a próxima tentativa. Escotilha de escape:
`Cache-Control: no-cache` no `POST` força a carga.

Quando a submissão é atendida por reuso (dado ainda quente ou job ativo), a
resposta segue `202` com o id do job existente: o cliente mantém um único fluxo, e
o `POST` passa a significar "garanta que o dado esteja quente" — idempotente no
tempo.

**5. Eventos por polling.** Sem pub/sub, o stream SSE deriva os eventos do estado:
consulta periódica e emissão quando `last_updated_at` muda. Como os eventos já
carregam estado e `lastUpdatedAt` (e não deltas), perder um intermediário entre
dois ciclos é inofensivo — decisão do ADR 0002 que passa a pagar dividendo aqui.
Consequência: `JobEventPublisherPortOut` deixa de existir; eventos são derivados,
não publicados.

## Esquema

```sql
create table async_jobs (
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

create unique index ux_async_jobs_idem on async_jobs (idempotency_key)
  where idempotency_key is not null;

create unique index ux_async_jobs_inflight on async_jobs (coalescing_key)
  where status in ('PENDING','PROCESSING');

create index ix_async_jobs_active on async_jobs (last_updated_at)
  where status in ('PENDING','PROCESSING');

create index ix_async_jobs_fresh on async_jobs (coalescing_key, last_updated_at desc)
  where status = 'COMPLETED';
```

A lib **não** distribui migrações: o DDL é documentado e o consumidor o aplica com
a ferramenta dele, para não acoplar Flyway/Liquibase a quem já tem o seu.

## Consequências

- Estado durável: o reaper deixa de compensar perda de estado por restart de nó e
  passa a cobrir apenas instância que caiu no meio do processamento.
- Garantia de leitura: comitar os dados **antes** de marcar `COMPLETED` já impede
  o estado perigoso (job concluído com dados invisíveis). Para eliminar também o
  caso "trabalho feito e job marcado como falho" (crash entre commit e marcação),
  a rotina pode incluir a conclusão na própria transação — o adapter JDBC
  participa da transação ambiente, usando o mesmo `DataSource`.
- Transação longa é o preço dessa garantia total; para rotinas de minutos, avaliar
  se o commit atômico do dado quente já não exige uma transação única de qualquer
  forma.
- **Retenção precisa ser maior que a maior janela de frescor**, senão a limpeza
  apaga a memória de que o dado está quente e as cargas voltam a repetir. Validado
  no startup.
- Progresso passa a ser escrita no primário: rotinas que reportam com muita
  frequência devem ser limitadas, com atenção a autovacuum na tabela.
- Lag de réplica vira questão de correção: status pelo primário (lookup por PK),
  leitura de domínio pela réplica.
- Filtro e agrupamento dinâmicos, adiados no ADR 0001, deixam de ser problema da
  lib — nascem de graça no SQL do consumidor.

## Migração

Pelo playbook da skill `hexagonal-architecture`: strangler, não big-bang. O
adapter JDBC entra **ao lado** do Redis (selecionável por `async-jobs.storage`),
com a suíte verde em cada passo; a remoção do Redis é o último passo, depois de o
caminho novo estar coberto por testes.

**Concluída em 2026-07.** `jdbc` é o storage default (`async-jobs.storage`
existe só para o consumidor optar por um repositório próprio), o Redisson saiu do
`pom.xml` e os ports que só existiam por causa do Redis foram removidos:
`SingleFlightPortOut` (absorvido pelo índice único), `JobEventPublisherPortOut`
(sem pub/sub, o subscriber deriva do estado), `JobResultStorePortOut` e
`untrackActive` (não há índice de ativos separado — "ativo" é um predicado na
tabela). A propriedade `async-jobs.key-prefix` perdeu sentido e saiu;
`async-jobs.result-ttl` virou `async-jobs.retention`, já que não há mais
resultado a expirar.

## Conformidade com o padrão (Azure Architecture Center)

Revisão item a item contra a especificação do Asynchronous Request-Reply.

Cumprido: `202` com `Location` e `Retry-After`; validação antes de iniciar o
processamento, com `400` para requisição inválida; endpoint de status **dedicado**
(a alternativa criticada pelo doc é pollar o recurso final e receber `404`
ambíguo); os cinco campos recomendados no corpo do status (`status`, `createdAt`,
`lastUpdatedAt`, `percentComplete` e erro); `303` em vez de `302`, pelo mesmo
motivo que o doc dá; falha devolvida como `4xx` em RFC 9457 a partir do recurso
que o `Location` aponta; retenção com header `Expires`; `DELETE` no recurso de
status para cancelar; e `Idempotency-Key` devolvendo o recurso existente em vez de
enfileirar segundo trabalho.

**O `303` para o recurso de domínio é mais fiel, não menos.** O doc diz que o
status redireciona "para a URL daquele recurso" quando a operação cria um recurso
novo. Aqui a operação não cria recurso: ela reaquece dados existentes. O
`/jobs/{id}/result` era um recurso sintético embrulhando dado alheio; apontar para
o endpoint de domínio é a leitura literal.

> **Nota de implementação (2026-07): o `303` ficou adiado.** O que foi
> construído é `200 OK` com `status: COMPLETED`, sem redirect. Redirecionar exige
> que a lib conheça a URL de domínio de cada `type` — uma configuração
> `async-jobs.types.<type>.result-url` que ninguém pediu ainda e que criaria uma
> dependência da lib para o mapa de rotas do consumidor. Como o cliente já
> descobre a conclusão pelo evento `complete` do SSE (ou pelo status), o redirect
> não elimina nenhum passo hoje: ele só se paga quando houver um cliente genérico
> que não sabe para onde ir. Reabrir quando esse cliente existir.

**Reuso por frescor tem respaldo direto.** A orientação de idempotência do doc já
manda devolver o recurso de status existente em vez de enfileirar segundo
trabalho. A janela de frescor generaliza isso de "mesma chave" para "mesma janela
de tempo", mantendo `202` + `Location`.

**Correção de desvio: cancelado passa a ser `200`.** Antes devolvíamos `410 Gone`
sem corpo, o que era invenção nossa — o doc trata `Canceled` como um valor do
campo `status` e diz que a conclusão do cancelamento "should update the status
resource to reflect the canceled state", ou seja, o recurso continua legível.
`4xx` é prescrito só para erro de processamento, e cancelamento não é erro. Agora
o status responde `200` com `status: CANCELLED`, preservando `createdAt`,
`lastUpdatedAt` e `Expires`, e `404` fica reservado para job inexistente.

Desvios que permanecem, conscientes:

- **Offload para "outro componente, como uma fila"**: despachamos para um executor
  no próprio processo, o que amarra o job à instância que recebeu o `POST`. Com
  Aurora existe uma evolução natural — a própria tabela `async_jobs` como fila via
  `FOR UPDATE SKIP LOCKED`, permitindo que qualquer instância puxe `PENDING` e
  tornando a varredura o mecanismo principal de distribuição em vez de rede de
  segurança. Fora do escopo desta ADR; registrado como opção.
- **O gate deixa de ser garantia**: com a leitura indo para um endpoint de domínio
  compartilhado, "só leia quando terminar" passa a ser conselho — nada impede o
  cliente de ler dado morno antes. É inerente à semântica de cache quente; por
  isso a frescura deve ser exposta na resposta de domínio.
- **Cancelar não interrompe a rotina em andamento**, e o doc pede para avaliar
  rollback parcial ou transação compensatória. Para reaquecimento não há o que
  compensar; se surgir rotina com efeito colateral externo, reabrir.
- **SSE como complemento, não alternativa**: o doc posiciona SSE na seção de
  "quando este padrão não é adequado". Mantemos o polling como baseline e SSE como
  transporte adicional — superconjunto deliberado, não contradição.

## Alternativas consideradas

- **Manter Redis só para pub/sub**: descartado por não valer um cluster dedicado
  quando polling em réplica resolve com latência de ~1s.
- **`LISTEN/NOTIFY`**: não passa por RDS Proxy, perde notificação em failover e
  exige conexão dedicada por instância. Reavaliável se a latência do polling
  incomodar.
- **Servir o resultado pela lib via port**: recriaria dentro de
  `page(jobId, page, size)` exatamente o problema de filtro que o ADR 0001 adiou.
