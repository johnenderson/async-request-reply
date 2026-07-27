# ADR 0002 — Server-Sent Events para notificação de jobs

- **Status:** Aceito (implementado); pontos revistos pelo ADR 0004 — marcados no texto
- **Data:** 2026-07
- **Contexto:** building block Asynchronous Request-Reply (na época, estado no Valkey/Redis e contrato HTTP de polling)

## Contexto

O contrato base do padrão é polling: `202 Accepted` + `Location`, e o client
consulta `GET /jobs/{id}/status` no intervalo sugerido por `Retry-After`.
Isso funciona em qualquer client/proxy, mas tem dois custos: latência média de
meio intervalo entre a conclusão e o client perceber, e tráfego de requests
que respondem "ainda não".

## Decisão

Adicionar um **transporte de notificação opcional** via SSE, sem alterar o
contrato existente:

- `GET /jobs/{id}/events` (`text/event-stream`) entrega um **snapshot** do
  estado atual seguido dos eventos de transição (`status`, `progress`,
  `complete`, `failed`, `cancelled`); no evento terminal o servidor fecha o
  stream.
- O **resultado não trafega pelo stream**: ~~`complete` carrega apenas a
  `resultUrl`; a leitura continua no endpoint REST paginado~~ →
  **revisto pelo ADR 0004**: não há endpoint de resultado. O `complete` carrega
  apenas `jobId` e `lastUpdatedAt` — é o sinal para o cliente ler o endpoint de
  domínio dele.
- ~~**Pub/sub do Redis (RTopic, `job:{id}:events`)** é a ponte entre instâncias:
  a transição publica onde o job processa; o stream vive onde o client
  conectou.~~ → **revisto pelo ADR 0004**: sem Redis não há pub/sub. Os eventos
  passam a ser **derivados do estado**, por polling do `last_updated_at` a cada
  `async-jobs.sse.poll-interval`. A propriedade de atravessar instâncias se
  mantém — e por um caminho mais simples, já que o banco é a fonte de verdade
  para as duas pontas. O custo é latência: até um intervalo de poll, contra o
  push imediato do pub/sub.
- Eventos são publicados **somente quando a transição atômica teve sucesso** —
  um evento nunca anuncia um estado que não foi persistido.
- A assinatura acontece **antes** da leitura do snapshot: uma transição no
  meio gera no pior caso um duplicado inofensivo (eventos carregam estado,
  não deltas), nunca uma perda.
- ~~**Opt-in**: `async-jobs.sse.enabled=true` (default `false`)~~ →
  **revisto em 2026-07**: o stream passou a fazer parte do contrato e a flag foi
  removida. A lib nunca foi publicada com a flag, então não há migração.

Com a remoção do plano de dados (ADR 0004), o SSE deixou de ser
*progressive enhancement* e virou o caminho **preferencial**: sem endpoint de
resultado, é o evento `complete` que diz ao cliente quando ir ler a base dele —
sem isso, sobraria só o polling que este ADR queria evitar.

## Consequências

- Polling permanece o baseline e o fallback; SSE é progressive enhancement.
- Reconexão do `EventSource` é barata: o servidor reenvia o snapshot — não há
  replay de eventos nem `Last-Event-ID`, pois o estado é materializado.
- Heartbeat (`async-jobs.sse.heartbeat`, default `PT15S`) mantém proxies de
  derrubar conexões ociosas; o timeout do emitter é o `async-jobs.retention`, o
  que limita o custo de streams de jobs "zumbis" (worker que nunca reporta).
- O subscriber por polling funciona sobre **qualquer** `JobRepositoryPortOut`,
  então storage próprio ganha SSE sem registrar nada — mas pode registrar um
  `JobEventSubscriberPortOut` próprio se tiver um caminho de push melhor.
- WebSocket foi descartado (bidirecionalidade desnecessária); long-polling
  também (pior em proxies e mais complexo no servidor).
