# ADR 0001 — Filtragem/agrupamento dinâmico do resultado (adiado)

- **Status:** Adiado (não implementado)
- **Data:** 2026-06
- **Contexto:** building block Asynchronous Request-Reply (resultado materializado em chunks no Valkey/Redis)

## Contexto

Hoje o resultado de um job é materializado como uma LIST no Redis/Valkey
(`job:{id}:result`) e o endpoint `GET /jobs/{id}/result?page=&size=` pagina
nativamente via `LLEN` (total) + `LRANGE` (página), sem carregar tudo em memória.

O job é **parameterless**: o `type` seleciona a rotina, e a própria rotina
(no projeto consumidor) já aplica os filtros que precisa ao materializar a base.
Ou seja: **não há filtro/agrupamento dinâmico por request** na leitura. Cada
`type` produz uma base; filtros diferentes ⇒ rotinas/`type`s diferentes.

## Decisão

**Não implementar** filtragem nem `groupBy` dinâmico (via query params) no
`GET /result` por enquanto. A leitura apenas pagina a base materializada.

Motivo: filtrar "qualquer coisa" sobre uma LIST do Redis sem índice é um
**scan O(n)**, que não escala. Suportar isso bem exige uma decisão de design
(quais filtros, quais índices) que **não temos necessidade hoje**.

## Quando reabrir

Reabrir esta decisão se surgir a necessidade de:

- o **mesmo `type`** retornar resultados diferentes conforme **input do cliente**
  (filtro/parâmetro por request), ou
- filtrar/agrupar a base materializada **na leitura**, dinamicamente.

Nesse momento, reintroduzir um conceito de parâmetro de submissão **sem perder a
paginação nativa** que temos hoje.

## Opções consideradas (para quando for necessário)

| Estratégia | Como | Quando usar |
|---|---|---|
| **Filtro em memória pós-LRANGE** | carrega a página/range e filtra na aplicação | filtros simples, volumes pequenos/médios |
| **Índices materializados** | a rotina grava LISTs por filtro suportado (ex: `job:{id}:result:ativos`); cada filtro vira uma LIST paginável própria | conjunto de filtros **conhecido e fixo**, definido antes |
| **RediSearch / Redis Query Engine** | indexa os itens (JSON) e consulta com filtros/agregações arbitrárias | filtros arbitrários, grandes volumes |

Recomendação inicial (se o conjunto de filtros for conhecido): **índices
materializados** — melhor custo/benefício e mantém a leitura O(página).

## Consequências de adiar

- Implementação atual mais simples e escalável para o caso real (base completa
  por `type` + paginação nativa).
- A capacidade de filtro/parametrização por request fica em aberto; introduzir
  depois é uma mudança aditiva (nova chave de result store / índice), não uma
  reescrita.
