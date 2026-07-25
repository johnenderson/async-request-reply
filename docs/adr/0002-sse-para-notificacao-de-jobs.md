# ADR 0002 — Server-Sent Events para notificação de jobs (opt-in)

- **Status:** Aceito (implementado)
- **Data:** 2026-07
- **Contexto:** building block Asynchronous Request-Reply (estado no Valkey/Redis, contrato HTTP de polling)

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
- O **resultado não trafega pelo stream**: `complete` carrega apenas a
  `resultUrl`; a leitura continua no endpoint REST paginado.
- **Pub/sub do Redis (RTopic, `job:{id}:events`)** é a ponte entre instâncias:
  a transição publica onde o job processa; o stream vive onde o client
  conectou.
- Eventos são publicados **somente quando a transição atômica teve sucesso** —
  um evento nunca anuncia um estado que não foi persistido.
- A assinatura acontece **antes** da leitura do snapshot: uma transição no
  meio gera no pior caso um duplicado inofensivo (eventos carregam estado,
  não deltas), nunca uma perda.
- ~~**Opt-in**: `async-jobs.sse.enabled=true` (default `false`)~~ →
  **revisto em 2026-07**: o stream passou a fazer parte do contrato e a flag foi
  removida. A lib nunca foi publicada com a flag, então não há migração. Storage
  próprio agora precisa fornecer `JobEventPublisherPortOut` e
  `JobEventSubscriberPortOut` (a ausência falha o startup).

## Consequências

- Polling permanece o baseline e o fallback; SSE é progressive enhancement.
- Reconexão do `EventSource` é barata: o servidor reenvia o snapshot — não há
  replay de eventos nem `Last-Event-ID`, pois o estado é materializado.
- Heartbeat (`async-jobs.sse.heartbeat`, default `PT15S`) mantém proxies de
  derrubar conexões ociosas; o timeout do emitter é o `result-ttl`, o que
  limita o custo de streams de jobs "zumbis" (worker que nunca reporta).
- Storage próprio (`async-jobs.storage != redis`) precisa registrar
  `JobEventPublisherPortOut`/`JobEventSubscriberPortOut` para ter SSE.
- WebSocket foi descartado (bidirecionalidade desnecessária); long-polling
  também (pior em proxies e mais complexo no servidor).
