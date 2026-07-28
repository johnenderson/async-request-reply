# Asynchronous Request-Reply Pattern

Building block em Java/Spring Boot para encapsular o padrao **Asynchronous Request-Reply**.

A ideia do projeto e oferecer uma fronteira HTTP padronizada para operacoes que nao devem bloquear a request original. O client submete um trabalho, recebe `202 Accepted` com a URL de acompanhamento, e e avisado quando terminar — por stream de eventos (SSE) ou por polling no recurso de status.

## O problema que este building block resolve

Em APIs HTTP, algumas operacoes levam tempo demais para serem executadas dentro de uma request sincrona: geracao de relatorios, processamento de arquivos, chamadas para sistemas externos, conciliacoes, cargas em lote ou qualquer rotina de longa duracao.

Este projeto encapsula esse fluxo:

1. O client envia uma requisicao para criar um job.
2. A API responde rapidamente com `202 Accepted`.
3. O processamento continua em background.
4. O client acompanha o progresso por SSE ou pela URL de status.
5. Ao concluir, o recurso de status passa a reportar `COMPLETED`.
6. O client le o resultado no **endpoint de dominio dele**.

### Escopo: plano de controle, nao plano de dados

A lib controla **execucao**, nao serve dados. Ela responde "essa carga esta em
andamento / terminou agora / falhou", e o dado propriamente dito e lido pelo
consumidor no endpoint de dominio dele, com o SQL otimizado que ele quiser.

Isso e deliberado (`docs/adr/0004-storage-em-aurora-postgresql-e-escopo-de-controle.md`):
o caso de uso que originou a lib e uma rotina que varre a base e a mantem quente.
O dado ja esta no Aurora ao fim da rotina — copiar esse resultado para dentro da
lib seria duplicar estado e limitar a leitura a uma paginacao generica.

## Arquitetura

O projeto segue uma organizacao inspirada em arquitetura hexagonal:

- `core/domain`: modelo de leitura do job (incluindo `JobFailure`); as transicoes atomicas ficam no adapter de persistencia.
- `core/usecase`: casos de uso da aplicacao, sem dependencia direta de HTTP.
- `core/service`: regras que atravessam casos de uso — hoje, `StaleJobRecoveryService`.
- `core/event`: `JobEvent`, derivado de cada mudanca de estado.
- `core/port/in`: portas de entrada usadas pelos adapters.
- `core/port/out`: portas de saida para persistencia, eventos, politica e processamento.
- `adapter/in/web`: controller HTTP, stream SSE (`sse/`) e mapeamento de erros para Problem Details.
- `adapter/out/persistence/jdbc`: persistencia dos jobs em PostgreSQL, sobre o `DataSource` da aplicacao.
- `adapter/out/events`: subscriber de eventos derivado do estado, por polling.
- `adapter/out/processing`: processamento assincrono no executor proprio da lib e dispatch para as rotinas.
- `adapter/out/recovery`: agendador da varredura de jobs orfaos.
- `adapter/out/policy`: politicas padrao de polling, retencao e frescor.
- `config`: propriedades (`AsyncJobsProperties`), em pacote neutro para os adapters nao dependerem de `autoconfigure`.
- `spi`: contrato que o projeto consumidor implementa para plugar rotinas reais.

O core nao tem anotacao de framework nem component-scan: use cases e services sao
classes simples, e todo o wiring vive em `autoconfigure` — um **composition root**
unico e auditavel. Cada bean e `@ConditionalOnMissingBean`, entao o consumidor
substitui qualquer peca declarando a sua.

## Componentes principais

- `Job`: modelo de leitura do job — `id`, `type`, status, progresso e falha.
- `JobHandler`: SPI implementada pelo projeto consumidor para cada rotina assincrona.
- `JobHandlerRegistry`: indexa as rotinas registradas por `type` e detecta duplicidade no startup.
- `SubmitJobUseCase`: decide se uma carga nova e necessaria (idempotencia → frescor → single-flight) e dispara o processamento.
- `AsyncJobProcessorAdapterOut`: executa o job em background e chama a rotina registrada para o `type`.
- `GetJobStatusUseCase`: traduz o estado do job para uma view de status.
- `JdbcJobRepositoryAdapterOut`: transicoes atomicas em SQL, sem lock distribuido.

## Storage

O storage e uma tabela em PostgreSQL (Aurora 18.3), no **banco do consumidor**:
a lib nao configura pool nem ativa `DataSourceAutoConfiguration` — ela usa o
`DataSource` que a aplicacao ja tem.

O esquema tambem e do consumidor, aplicado com a ferramenta de migracao dele. O
DDL de referencia esta em `src/main/resources/async-jobs-schema.sql` e e o mesmo
que a suite de testes aplica, para o esquema documentado e o testado nao
divergirem.

Concorrencia por **constraint**, nao por lock:

- `ux_async_jobs_idem`: indice unico parcial sobre `idempotency_key` — o retry da mesma key nunca cria um segundo job.
- `ux_async_jobs_inflight`: indice unico parcial sobre `coalescing_key` nos estados ativos — o single-flight e o banco recusando o segundo insert, e o perdedor recebe o job do vencedor.
- Transicoes sao `UPDATE ... WHERE status IN (...) RETURNING last_updated_at`: check-and-set em um statement, sem leitura previa.

## Fluxo HTTP

### Submeter um job

```http
POST /jobs/{type}
Idempotency-Key: opcional
Cache-Control: no-cache   (opcional)
```

O `type` no path precisa corresponder a uma rotina registrada no contexto Spring e deve usar apenas letras, números, ponto, hífen ou underscore (`[A-Za-z0-9._-]+`).

O `POST` significa "garanta que este trabalho esteja feito", nao "rode agora": se
o dado ainda esta quente (ver [janela de frescor](#janela-de-frescor)), a lib
devolve a carga anterior em vez de refazer o trabalho. `Cache-Control: no-cache`
forca uma carga nova.

Resposta:

```http
202 Accepted
Location: /jobs/{jobId}/status
Retry-After: 5
```

```json
{
  "jobId": "uuid-do-job",
  "statusUrl": "http://localhost:8080/jobs/uuid-do-job/status",
  "eventsUrl": "http://localhost:8080/jobs/uuid-do-job/events"
}
```

Se o `type` nao existir, a API retorna `400 Bad Request` com `ProblemDetail`.

### Consultar status

```http
GET /jobs/{id}/status
```

Possiveis respostas:

- `200 OK`: job em qualquer estado nao-falho — `PENDING`, `PROCESSING`, `COMPLETED` ou `CANCELLED`.
- `404 Not Found`: job inexistente.
- `422 Unprocessable Content`: job falhou (Problem Details, RFC 9457).

Enquanto o job esta ativo, a resposta traz `Retry-After`. Nos estados terminais o
`Retry-After` desaparece e o `Expires` indica ate quando o recurso segue
disponivel.

Nao ha redirect: o padrao preve `303 See Other` para um recurso de resultado, e
como a lib nao serve dados, quem sabe a URL do resultado e o consumidor. Ao ver
`COMPLETED`, ele le o endpoint de dominio dele.

Job cancelado tambem responde `200 OK`, com `status: CANCELLED` no corpo: o
padrao trata cancelado como um valor de status, e nao como recurso que deixou de
existir — assim o cliente continua lendo `createdAt`, `lastUpdatedAt` e `Expires`
sem tratamento especial, e `404` fica reservado para job inexistente.

Exemplo de job em processamento:

```json
{
  "jobId": "uuid-do-job",
  "status": "PROCESSING",
  "createdAt": "2026-06-03T22:00:00Z",
  "lastUpdatedAt": "2026-06-03T22:00:03Z",
  "percentComplete": null
}
```

### Acompanhar por eventos (SSE)

```http
GET /jobs/{id}/events
Accept: text/event-stream
```

O stream entrega um snapshot do estado atual e os eventos de transicao ate o
estado terminal, quando o servidor fecha a conexao:

```
event:status
data:{"jobId":"...","status":"PROCESSING","percentComplete":null,"lastUpdatedAt":"2026-06-03T22:00:03Z"}

event:progress
data:{"jobId":"...","status":"PROCESSING","percentComplete":40,"lastUpdatedAt":"2026-06-03T22:00:07Z"}

event:complete
data:{"jobId":"...","lastUpdatedAt":"2026-06-03T22:00:09Z"}
```

- `404 Not Found`: job inexistente.
- Eventos possiveis: `status`, `progress`, `complete`, `failed`, `cancelled`.
- O `complete` diz apenas "terminou, neste instante" — e o sinal para o cliente ir ler o endpoint de dominio dele.
- O polling via `GET /status` continua funcionando normalmente — SSE e um transporte adicional, nao um substituto.
- Na reconexao (automatica no `EventSource`), o servidor reenvia o snapshot; como o estado e materializado, nao ha replay de eventos.
- Todo evento carrega `lastUpdatedAt` (o instante realmente persistido). Numa corrida entre o snapshot inicial e um evento novo, o cliente mantem o de maior timestamp e descarta o mais antigo.
- Os eventos sao **derivados do estado**: o subscriber compara o `last_updated_at` do job a cada `async-jobs.sse.poll-interval`. Nao ha pub/sub, e a transicao pode ter acontecido em outra instancia — o stream a percebe do mesmo jeito, porque a fonte de verdade e o banco.

Decisao registrada em `docs/adr/0002-sse-para-notificacao-de-jobs.md`.

### Cancelar um job

```http
DELETE /jobs/{id}
```

A rota `DELETE /jobs/{id}/status` (contrato original) segue aceita como alias.

Possiveis respostas:

- `202 Accepted`: cancelamento aceito.
- `409 Conflict`: job ja esta em estado terminal.
- `404 Not Found`: job inexistente.

> Nota: o cancelamento marca o job como `CANCELLED` e impede que uma conclusao
> posterior sobrescreva o estado, mas **nao interrompe** a thread da rotina.
> Interromper trabalho a meio caminho deixaria a base do consumidor em estado
> parcial, sem ninguem para consertar — entao quem decide parar e a propria
> rotina, consultando `ctx.isCancelled()` entre lotes
> ([cancelamento cooperativo](#cancelamento-cooperativo)).

## Estados do job

O dominio trabalha com os seguintes estados:

- `PENDING`: job criado e aguardando processamento.
- `PROCESSING`: job em execucao.
- `COMPLETED`: job finalizado com sucesso.
- `FAILED`: job finalizado com erro.
- `CANCELLED`: job cancelado.

## SPI para projetos consumidores

Cada rotina assincrona deve ser exposta como um bean Spring que implementa `JobHandler`:

```java
@Component
public class ManterContasQuentesHandler implements JobHandler {

    private final ContaRepository contas;

    @Override
    public String type() {
        return "contas";
    }

    @Override
    public void handle(JobContext ctx) {
        // regra de negocio do projeto consumidor: varre as contas,
        // avalia a aptidao de cada uma e grava o resultado na base
        for (var lote : contas.emLotes(500)) {
            if (ctx.isCancelled()) {
                return;                  // para num ponto consistente
            }
            contas.reavaliar(lote);
        }
    }
}
```

O `handle` nao retorna nada: a rotina deixa o resultado na base do consumidor, e
a lib apenas registra que a carga terminou.

Regras importantes:

- Cada `type` deve ser unico.
- Se houver duas rotinas com o mesmo `type`, a aplicacao falha no startup.
- Se o request usar um `type` sem rotina registrada, a API retorna `400 Bad Request`.

Para rotinas fire-and-forget existe a variante `AsyncJobHandler`: a lib apenas
dispara `start(ctx)` e mantem o job em `PROCESSING` ate o worker reportar via
`JobReporter` (`progress`/`complete`/`fail`).

Todos os metodos do `JobReporter` retornam `boolean`: `false` significa que o
report foi **recusado** porque o job nao existe mais ou ja esta em estado
terminal (tipicamente foi cancelado). Um worker que recebe `false` deve parar o
trabalho em vez de seguir reportando.

Se o worker morrer sem reportar, a recuperacao automatica marca o job como falho
apos `async-jobs.recovery.processing-timeout`. Rotinas legitimamente longas devem
chamar `progress` periodicamente para renovar esse prazo.

### Cancelamento cooperativo

`ctx.isCancelled()` diz se o job foi cancelado enquanto a rotina roda. A lib nao
interrompe a thread de proposito: abortar no meio deixaria a base do consumidor
parcialmente atualizada, e so a rotina sabe onde e seguro parar.

- **Cada chamada le o storage** — pergunte entre lotes, nao a cada item.
- Rotinas que ja chamam `progress` periodicamente **nao precisam disto**: o
  `false` devolvido por `progress` carrega a mesma informacao, sem leitura extra.
- Parar nao muda o estado: o job permanece `CANCELLED`. Uma rotina que retorna
  normalmente depois de parar nao "descancela" o job — a transicao para
  `COMPLETED` e recusada em estado terminal.

### Saber de quando sao os dados

Como a lib nao serve dados, nada impede o cliente de ler a base antes de a carga
terminar. O `JobFreshness` existe para a resposta de dominio poder dizer isso:

```java
@GetMapping("/contas/aptas")
ResponseEntity<ContasResponse> aptas() {
    var contas = repository.buscarAptas();          // SQL de dominio, otimizado
    return ResponseEntity.ok(new ContasResponse(
            contas,
            freshness.lastRefreshedAt("contas").orElse(null),   // "dados de"
            freshness.isFresh("contas")));                      // dentro da janela?
}
```

- `lastRefreshedAt(type)`: quando a ultima carga concluiu — a idade real dos
  dados. Vazio se nenhuma carga concluiu; uma carga que falhou nao conta.
- `isFresh(type)`: se essa conclusao esta dentro da janela configurada. Sempre
  `false` sem janela configurada, pela mesma razao que toda submissao dispara
  carga nesse caso.

## Politicas implementadas

- **Polling hint**: respostas usam `Retry-After` para orientar quando o client deve consultar novamente.
- **Retencao**: `async-jobs.retention` (padrao: 1 hora) define por quanto tempo o registro de controle segue relevante. Alimenta o header `Expires` e o timeout do stream SSE; o expurgo em si e do consumidor, dono do esquema.
- **Single-flight opcional**: `async-jobs.coalesce-in-flight=true` colapsa requests equivalentes enquanto ha job ativo daquele `type`. Garantido por indice unico parcial, sem lock: o segundo insert e recusado pelo banco e o cliente recebe o job em andamento.
- **Janela de frescor**: ver secao abaixo.
- **Idempotencia**: `Idempotency-Key` permite reutilizar o job criado para uma submissao equivalente. Reusar a mesma key com um `type` diferente e rejeitado com `422` (a key so vale para retries da MESMA operacao).
- **Problem Details**: falhas de dominio sao traduzidas para `ProblemDetail`.
- **Eventos em tempo real**: `GET /jobs/{id}/events` (SSE) entrega snapshot + transicoes. Faz parte do contrato, sem flag para desligar.
- **Execucao isolada**: os jobs rodam em um executor proprio da lib com **threads virtuais**, nunca no executor default da aplicacao; o limite de concorrencia (`async-jobs.processing.concurrency-limit`) e o backpressure.
- **Recuperacao de jobs orfaos**: a varredura reenfileira jobs que ficaram `PENDING` (instancia caiu antes de processar) e falha `PROCESSING` sem atualizacao ha muito tempo (worker morreu sem reportar). Ela so age sobre `type`s registrados na instancia, para nao interferir em jobs de outra aplicacao no mesmo banco.
- **Cancelamento cooperativo**: `ctx.isCancelled()` deixa a rotina parar num ponto consistente. A lib nao interrompe a thread — ver a secao da SPI.
- **Frescura consultavel**: `JobFreshness` permite ao endpoint de dominio dizer de quando sao os dados que esta devolvendo.

Como o submit nao recebe parametros, o dedupe/single-flight e por `type`.
Operacoes logicamente distintas devem usar `type`s distintos.

### Janela de frescor

Dados que nao mudam a todo momento nao precisam ser recarregados a cada request.
Se uma carga do mesmo escopo concluiu ha menos que a janela configurada, o dado
segue quente e a lib devolve aquele job em vez de disparar uma nova execucao.

```yaml
async-jobs:
  retention: PT6H
  coalesce-in-flight: true
  freshness:
    enabled: true
    default-window: PT1H
    per-type:
      contas: PT30M
```

- E **opt-in**: sem `freshness.enabled=true`, todo submit dispara carga nova.
- Exige `coalesce-in-flight=true`, e o startup falha se so o frescor for ligado:
  a janela localiza a ultima carga concluida pelo escopo de coalescing, que so e
  gravado quando o coalescing esta ligado. Sem a validacao, ligar so o frescor
  seria um no-op silencioso.
- Sem janela configurada para o `type` (nem `default-window`), o frescor nao se aplica.
- So conta job `COMPLETED`: uma carga que falhou nao suprime a proxima tentativa.
- `Cache-Control: no-cache` no submit ignora a janela e forca uma carga nova.
- A janela nao pode ser maior que `async-jobs.retention` — seria contraditorio (o job concluido seria expurgado antes de a janela fechar) e o startup falha.

Frescor e single-flight sao janelas diferentes e complementares: single-flight
cobre job **ainda em andamento**; frescor cobre carga **ja concluida**.

## Configuracao

A lib usa o `DataSource` da aplicacao e algumas propriedades proprias sob `async-jobs`.

Exemplo de `application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/meubanco
    username: app
    password: secret

async-jobs:
  storage: jdbc
  web:
    enabled: true
  retention: PT1H
  retry-after-seconds: 5
  coalesce-in-flight: false
  processing:
    concurrency-limit: 256
  sse:
    heartbeat: PT15S
    max-pending-events: 64
    poll-interval: PT1S
  freshness:
    enabled: false
  recovery:
    enabled: true
    scan-interval: PT30S
    redispatch-after: PT1M
    processing-timeout: PT15M
    batch-size: 100
```

Nenhuma dessas propriedades e obrigatoria — os valores acima sao os defaults.

Parametros proprios:

| Propriedade | Padrao | Descricao |
| --- | --- | --- |
| `async-jobs.storage` | `jdbc` | Seleciona a auto-configuracao de storage. Com `jdbc`, a lib registra o repositorio sobre o `DataSource` da aplicacao. Para storage proprio, use outro valor e registre um bean `JobRepositoryPortOut`. |
| `async-jobs.web.enabled` | `true` | Liga/desliga o adapter web servlet (`/jobs`). Quando `false`, a lib funciona apenas como motor/use cases, sem expor endpoints HTTP. |
| `async-jobs.retention` | `PT1H` | Por quanto tempo o registro de controle do job segue relevante. Alimenta o header `Expires` (a partir da ultima atualizacao) e o timeout do stream SSE. Aceita formato `Duration` do Spring, como `PT10M`, `PT1H` ou `P1D`. |
| `async-jobs.retry-after-seconds` | `5` | Hint enviado no header `Retry-After` em submissao e consulta de status enquanto o job esta ativo. |
| `async-jobs.coalesce-in-flight` | `false` | Quando `true`, chamadas equivalentes enquanto um job ainda esta ativo reutilizam o mesmo job em andamento em vez de criar outro. |
| `async-jobs.freshness.enabled` | `false` | Liga a janela de frescor: uma carga concluida dentro da janela dispensa carga nova. Exige `coalesce-in-flight=true`. |
| `async-jobs.freshness.default-window` | — | Janela aplicada aos `type`s sem configuracao propria. Sem valor, o frescor nao se aplica a eles. |
| `async-jobs.freshness.per-type.<type>` | — | Janela especifica de um `type`, sobrepondo a default. |
| `async-jobs.processing.concurrency-limit` | `256` | Jobs processados simultaneamente no executor proprio da lib (threads virtuais). Ao saturar, a submissao aguarda vaga — backpressure em vez de acumulo ilimitado. |
| `async-jobs.sse.heartbeat` | `PT15S` | Intervalo do comentario keep-alive enviado nos streams SSE abertos, para proxies nao derrubarem conexoes ociosas. |
| `async-jobs.sse.max-pending-events` | `64` | Backlog maximo de eventos por stream. Um cliente que nao drena o socket e desconectado ao estourar esse limite, em vez de acumular memoria. |
| `async-jobs.sse.poll-interval` | `PT1S` | Intervalo entre leituras do estado num stream aberto — e, portanto, a latencia maxima do evento. |
| `async-jobs.recovery.enabled` | `true` | Liga a varredura de jobs orfaos. Desligar significa que um job cuja instancia caiu fica `PENDING` para sempre. |
| `async-jobs.recovery.scan-interval` | `PT30S` | Frequencia da varredura. |
| `async-jobs.recovery.redispatch-after` | `PT1M` | Tempo em `PENDING` sem avanco antes de reenfileirar o job. |
| `async-jobs.recovery.processing-timeout` | `PT15M` | Tempo em `PROCESSING` sem atualizacao antes de declarar o job falho. Rotinas legitimamente longas devem reportar progresso para renovar o prazo. |
| `async-jobs.recovery.batch-size` | `100` | Maximo de jobs inspecionados por varredura. |

Os hints de polling e retencao sao centralizados em `JobPolicyPortOut`. A
implementacao default (`DefaultJobPolicyAdapterOut`) evita espalhar no core ou no
controller decisoes como intervalo sugerido de polling e data de expiracao do
recurso. Se o projeto consumidor precisar de uma politica propria, basta registrar
um bean `JobPolicyPortOut` — a auto-configuracao so cria a default quando nao
existe outro bean desse tipo.

## Como executar

Requisitos:

- Java 25
- Maven Wrapper incluso no projeto
- Docker (para os testes de integracao)

Execute os testes:

```bash
./mvnw test
```

A suite usa Testcontainers para subir o PostgreSQL automaticamente; nao e
necessario manter um banco rodando em `localhost`.

Submeta um job:

```bash
curl -i -X POST http://localhost:8080/jobs/contas \
  -H "Idempotency-Key: exemplo-1"
```

Para esse exemplo funcionar em runtime, precisa existir um bean `JobHandler` cujo
`type()` retorne `contas`, e o esquema de `async-jobs-schema.sql` precisa estar
aplicado.

## Cobertura atual

A suite de testes registra rotinas de exemplo e cobre:

- submissao com `202 Accepted`, `Location`, `Retry-After` e as duas URLs de acompanhamento;
- rejeicao de `type` desconhecido com `400 Bad Request`;
- idempotencia via `Idempotency-Key` e conflito quando reusada com outro `type` (`422`);
- consulta de status com `Retry-After` e `Expires`;
- estado terminal legivel em `200 OK` (`COMPLETED` e `CANCELLED`), sem redirect;
- cancelamento pela rota canonica e pelo alias, e conclusao tardia que nao sobrescreve o cancelamento;
- `404 Not Found` para job inexistente, inclusive quando o id nem tem forma de UUID (nao `500`);
- single-flight (coalescing) por indice unico, inclusive com submits concorrentes;
- janela de frescor: carga suprimida com dado quente, refeita com `Cache-Control: no-cache`;
- frescura consultavel: `lastRefreshedAt` ignora carga ativa e carga que falhou, e `isFresh` e falso sem janela configurada;
- cancelamento cooperativo: a rotina em execucao ve `isCancelled()` virar `true`, para no meio, e o `complete` seguinte e recusado;
- job com falha (`422` + Problem Detail no status), inclusive com titulo em branco;
- report recusado em job terminal (`JobReporter` devolvendo `false`);
- recuperacao de jobs orfaos: reenfileiramento de `PENDING`, falha de `PROCESSING` zumbi e nao-interferencia em `type` de outra aplicacao;
- validacao de configuracao: frescor maior que a retencao, ou frescor sem coalescing, falham o startup;
- timestamps e `Expires` determinísticos com um `Clock` fixo injetado;
- fluxo fire-and-forget via `JobReporter`.

A suite tem duas camadas. Os testes **unitarios** cobrem use cases, services,
politicas e o controller com fakes/Mockito, sem subir contexto (rodam em
milissegundos). Os testes de **integracao** (marcados `@Tag("integration")`)
usam Testcontainers com PostgreSQL real, incluindo o contrato SQL do repositorio.

Alem dos testes MockMvc, `TomcatEndToEndIntegrationTest` sobe um Tomcat real
em porta aleatoria (`webEnvironment = RANDOM_PORT`) e exercita o fluxo
principal pela borda HTTP de verdade: URLs absolutas nos headers/body, formato
IMF-fixdate do `Expires`, ausencia de redirect, cancelamento, idempotencia e o
stream SSE (snapshot → `event:complete` → fechamento do stream pelo servidor).

Ultima verificacao local:

```bash
./mvnw clean test
```

Resultado: `Tests run: 143, Failures: 0, Errors: 0, Skipped: 0`.

Para rodar só os rápidos: `./mvnw test -Dgroups='!integration'`.

## Evolucao registrada

O dispatch e in-process: o job roda na instancia que recebeu o `POST`, e a
varredura de recuperacao e a rede de segurancia. A alternativa — a propria tabela
como fila, via `FOR UPDATE SKIP LOCKED` — esta desenhada no
`docs/adr/0005-dispatch-por-fila-na-propria-tabela.md`, junto com o critério de
quando vale implementar. Resumo: ganha distribuicao real entre instancias, custa
latencia de um ciclo de poll e uma peca viva a mais; para uma rotina de
reaquecimento com single-flight ligado (um job ativo por escopo), nao ha fila a
balancear.
