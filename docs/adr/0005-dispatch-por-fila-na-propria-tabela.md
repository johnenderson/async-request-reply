# ADR 0005 — Dispatch por fila na própria tabela (`FOR UPDATE SKIP LOCKED`)

- **Status:** Proposto (não implementado)
- **Data:** 2026-07
- **Contexto:** desvio consciente registrado no ADR 0004 — o offload do padrão

## Contexto

O padrão Asynchronous Request-Reply pede que a requisição seja repassada "para
outro componente, como uma fila". Hoje o `SubmitJobUseCase` chama
`processor.process(job)`, que é `@Async` no executor da própria lib. Consequência:
**o job fica amarrado à instância que recebeu o `POST`**.

Isso produz três problemas, em ordem de gravidade:

1. **Distribuição desigual.** Se o load balancer manda dois `POST` para a mesma
   instância, os dois jobs rodam lá, mesmo com outra instância ociosa. Não há
   como uma instância "puxar" trabalho.
2. **Recuperação como caminho principal, não como rede de segurança.** Quando a
   instância cai, o job só volta a andar quando o `StaleJobRecoveryService` o
   encontra — depois de `redispatch-after` (default `PT1M`). O que deveria ser
   exceção virou o mecanismo normal de redistribuição.
3. **Backpressure que rejeita em vez de enfileirar.** Ao saturar o
   `concurrency-limit`, o submit espera vaga; se o executor recusar, o job fica
   `PENDING` até a varredura. Já é tratado (a submissão devolve `202` de todo
   jeito), mas é acidente, não desenho.

Com o storage relacional, a própria tabela `async_jobs` pode ser a fila — não é
preciso introduzir SQS, RabbitMQ ou Kafka para resolver isso.

## Decisão proposta

Inverter o dispatch de **push** para **pull**. Cada instância roda um laço que
reclama jobs `PENDING` com `FOR UPDATE SKIP LOCKED`:

```sql
update async_jobs
   set status = 'PROCESSING', last_updated_at = now()
 where id in (
       select id from async_jobs
        where status = 'PENDING'
        order by created_at
        limit :batch
          for update skip locked)
returning id, type;
```

`SKIP LOCKED` é o ponto central: duas instâncias rodando este statement ao mesmo
tempo **não bloqueiam uma à outra e não pegam as mesmas linhas** — a segunda
simplesmente pula as linhas travadas pela primeira. É o mesmo mecanismo que
bibliotecas de fila em Postgres usam, e evita tanto o lock global quanto o
processamento em duplicidade.

O `SubmitJobUseCase` passa a apenas persistir `PENDING` e devolver `202`. O
`processor.process(job)` direto some do caminho de submissão.

## Consequências

**A favor:**

- Qualquer instância puxa qualquer job: distribuição real, sem afinidade com quem
  atendeu o `POST`.
- A instância que cai deixa de ser um problema de recuperação e passa a ser um
  não-evento: a transação não commitou, o lock caiu, e o job volta a ser visível
  para os outros no próximo ciclo.
- O `StaleJobRecoveryService` volta a ser o que o nome diz — rede de segurança
  para o zumbi em `PROCESSING`, não distribuidor de trabalho.
- O `202` deixa de depender de o executor local aceitar a tarefa.

**Contra:**

- **Latência mínima de um ciclo de poll.** Hoje o dispatch é imediato; com fila
  passa a esperar até o intervalo do laço. Para rotinas de minutos (a premissa do
  padrão) é irrelevante, mas piora a experiência dos testes e de jobs triviais.
- **Carga constante no banco.** Um `SELECT ... FOR UPDATE SKIP LOCKED` por
  instância por ciclo, mesmo sem trabalho. Mitigável com backoff quando a fila
  vem vazia, e é uma tabela pequena com índice parcial.
- **Mais uma peça viva.** Um laço por instância, com shutdown ordenado, que não
  existe hoje.
- **Atenção a autovacuum.** A tabela passa a ter `UPDATE` frequente; se o volume
  crescer, dá bloat.

## Por que não agora

O ganho é de **distribuição e resiliência sob múltiplas instâncias**, e o custo é
latência mais uma peça viva no ciclo de vida. Para o caso de uso que originou a
lib — uma rotina de reaquecimento por `type`, com single-flight ligado, ou seja
**um job ativo por escopo de cada vez** — a distribuição não é o gargalo: não há
fila de trabalho para balancear.

Vale implementar quando aparecer pelo menos um destes:

- mais de um job ativo por instância de forma rotineira (single-flight desligado,
  ou muitos `type`s distintos);
- jobs perdendo tempo em `PENDING` porque a instância que os aceitou está
  saturada enquanto outras estão ociosas;
- necessidade de que a queda de uma instância seja transparente, sem esperar
  `redispatch-after`.

Até então, o dispatch in-process com varredura de recuperação entrega a mesma
garantia funcional — nenhum job fica órfão — com menos partes móveis.

## Alternativas consideradas

- **Broker dedicado (SQS/Rabbit/Kafka).** Resolve o mesmo problema e adiciona
  infraestrutura, um segundo storage a manter consistente com o banco, e o
  problema de outbox. O ADR 0004 acabou de remover um storage; reintroduzir outro
  para dispatch anda na direção contrária.
- **`LISTEN/NOTIFY` para acordar o laço** e evitar o poll constante. Atrativo,
  mas não passa por RDS Proxy, perde notificação em failover e exige conexão
  dedicada por instância. Serviria como otimização de latência **em cima** da
  fila, nunca como o mecanismo de reivindicação — a garantia tem que vir do
  `SKIP LOCKED`.
- **Advisory locks** (`pg_try_advisory_lock`) por job. Funciona, mas o lock vive
  fora da linha e não aparece em `SELECT`; diagnosticar "quem está com este job"
  fica pior do que com estado na própria tabela.
