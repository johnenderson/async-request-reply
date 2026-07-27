# ADR 0003 — Execução isolada em threads virtuais e recuperação de jobs órfãos

- **Status:** Aceito (implementado)
- **Data:** 2026-07
- **Contexto:** building block Asynchronous Request-Reply (dispatch in-process com `@Async`)

## Contexto

Duas fragilidades do desenho original apareceram em revisão:

1. **Sequestro do executor da aplicação.** `@EnableAsync` sem qualificador fazia
   o processamento rodar no `applicationTaskExecutor` do Boot — o mesmo pool do
   `@Async` do projeto consumidor. Rotinas de longa duração (a premissa do
   padrão) competiam com as tarefas assíncronas da aplicação, e a fila ilimitada
   default significava ausência de backpressure.

2. **Jobs perdidos em restart ou crash.** O dispatch era puramente in-JVM: nada
   relia o storage procurando jobs `PENDING`. Se a instância caísse, o job ficava
   `PENDING` até o TTL e o cliente fazia polling eterno — sem timeout, sem
   reaper, sem retry. Um `AsyncJobHandler` cujo worker morresse deixava o job
   `PROCESSING` para sempre.

## Decisão

**Executor próprio com threads virtuais.** A lib registra
`asyncJobsExecutor` (`SimpleAsyncTaskExecutor` com `virtualThreads=true`) e o
processor usa `@Async("asyncJobsExecutor")`. Threads virtuais porque o trabalho
típico é I/O-bound e bloqueante: bloquear não consome thread de plataforma.
`async-jobs.processing.concurrency-limit` (default 256) é o backpressure — ao
saturar, a submissão aguarda vaga em vez de acumular trabalho sem limite.

**Índice de ativos + varredura de recuperação.** Um sorted set
(`jobs:active`, score = última atualização) é mantido no **mesmo MULTI/EXEC** da
transição de estado, então nunca dessincroniza do hash do job. Uma varredura
periódica (`async-jobs.recovery.*`, scheduler próprio da lib — sem exigir
`@EnableScheduling` na aplicação) aplica:

- `PENDING` parado há mais de `redispatch-after` → reenfileira. É seguro porque
  `start` é check-and-set atômico: duas instâncias reenfileirando não processam
  em duplicidade.
- `PROCESSING` sem atualização há mais de `processing-timeout` → marca como
  falho (worker morreu sem reportar).
- id indexado cujo job já expirou → sai do índice.

## Consequências

- O contrato ganha uma obrigação para o consumidor: rotinas legitimamente mais
  longas que `processing-timeout` **devem** reportar `progress`, que renova o
  prazo. Sem isso, serão declaradas falhas enquanto ainda rodam.
- A recuperação é best-effort e idempotente, não uma garantia transacional: um
  job pode ser reprocessado se a primeira execução morreu depois de efeitos
  colaterais externos. Handlers devem ser idempotentes.
- Múltiplas instâncias varrem em paralelo sem coordenação; as transições atômicas
  tornam isso inofensivo (apenas leituras redundantes), então não há lock
  distribuído no caminho da varredura.
- Saturação do limite de concorrência bloqueia a thread da request no submit.
  Com o default generoso isso é raro, e é preferível a aceitar `202` para
  trabalho que só acumularia em memória.
