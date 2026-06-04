# Asynchronous Request-Reply Pattern

Building block em Java/Spring Boot para encapsular o padrao **Asynchronous Request-Reply**.

A ideia do projeto e oferecer uma fronteira HTTP padronizada para operacoes que nao devem bloquear a request original. O client submete um trabalho, recebe `202 Accepted` com a URL de acompanhamento, consulta o status periodicamente e busca o resultado quando o job estiver concluido.

## O problema que este building block resolve

Em APIs HTTP, algumas operacoes levam tempo demais para serem executadas dentro de uma request sincrona: geracao de relatorios, processamento de arquivos, chamadas para sistemas externos, conciliacoes, cargas em lote ou qualquer rotina de longa duracao.

Este projeto encapsula esse fluxo:

1. O client envia uma requisicao para criar um job.
2. A API responde rapidamente com `202 Accepted`.
3. O processamento continua em background.
4. O client acompanha o progresso por uma URL de status.
5. Ao concluir, a API redireciona para a URL de resultado.
6. O client recupera o resultado, com suporte a paginacao.

## Arquitetura

O projeto segue uma organizacao inspirada em arquitetura hexagonal:

- `core/domain`: modelo de leitura do job; as transicoes atomicas ficam no adapter de persistencia.
- `core/usecase`: casos de uso da aplicacao, sem dependencia direta de HTTP.
- `core/port/in`: portas de entrada usadas pelos adapters.
- `core/port/out`: portas de saida para persistencia, politica e processamento.
- `adapter/in/web`: controller HTTP e mapeamento de erros para Problem Details.
- `adapter/out/persistence`: persistencia dos jobs em Valkey/Redis via Redisson.
- `adapter/out/processing`: processamento assincrono baseado em `@Async` e dispatch para handlers.
- `adapter/out/policy`: politicas padrao de polling e retencao.
- `spi`: contrato que o projeto consumidor implementa para plugar rotinas reais.

## Componentes principais

- `Job`: entidade de dominio que guarda `id`, `type`, `payload`, status, progresso, resultado e falha.
- `JobHandler<P, R>`: SPI implementada pelo projeto consumidor para cada rotina assincrona.
- `JobHandlerRegistry`: indexa os handlers registrados por `type` e detecta duplicidade no startup.
- `SubmitJobUseCase`: valida `type` e `payload`, aplica idempotencia e dispara o processamento.
- `AsyncJobProcessorAdapterOut`: executa o job em background, converte o payload para o tipo de entrada do handler e salva o resultado.
- `GetJobStatusUseCase`: traduz o estado do job para uma view de status.
- `GetJobResultUseCase`: entrega o resultado concluido em formato paginado.

## Fluxo HTTP

### Submeter um job

```http
POST /jobs
Content-Type: application/json
Idempotency-Key: opcional
```

Payload esperado:

```json
{
  "type": "relatorio",
  "payload": {
    "mes": "junho",
    "ano": 2026
  }
}
```

O campo `type` precisa corresponder a um `JobHandler` registrado no contexto Spring.

Resposta:

```http
202 Accepted
Location: /jobs/{jobId}/status
Retry-After: 5
```

```json
{
  "jobId": "uuid-do-job",
  "statusUrl": "http://localhost:8080/jobs/uuid-do-job/status"
}
```

Se o `type` nao existir, a API retorna `400 Bad Request` com `ProblemDetail`.

### Consultar status

```http
GET /jobs/{id}/status
```

Possiveis respostas:

- `200 OK`: job pendente ou em processamento.
- `303 See Other`: job concluido; o header `Location` aponta para `/jobs/{id}/result`.
- `404 Not Found`: job inexistente.
- `410 Gone`: job cancelado.
- `422 Unprocessable Content`: job falhou.

Exemplo de job em processamento:

```json
{
  "jobId": "uuid-do-job",
  "status": "PROCESSING",
  "result": null,
  "createdAt": "2026-06-03T22:00:00Z",
  "lastUpdatedAt": "2026-06-03T22:00:03Z",
  "percentComplete": null
}
```

### Buscar resultado

```http
GET /jobs/{id}/result?page=0&size=20
```

Possiveis respostas:

- `200 OK`: resultado disponivel.
- `409 Conflict`: job ainda nao foi concluido.
- `404 Not Found`: job inexistente.

O resultado e sempre retornado como pagina. Se o handler retornar uma lista, a lista e fatiada conforme `page` e `size`. Se retornar um objeto unico, esse objeto vira uma pagina com um item.

Exemplo:

```json
{
  "jobId": "uuid-do-job",
  "content": ["a", "b", "c"],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```

### Cancelar um job

```http
DELETE /jobs/{id}/status
```

Possiveis respostas:

- `202 Accepted`: cancelamento aceito.
- `409 Conflict`: job ja esta em estado terminal.
- `404 Not Found`: job inexistente.

## Estados do job

O dominio trabalha com os seguintes estados:

- `PENDING`: job criado e aguardando processamento.
- `PROCESSING`: job em execucao.
- `COMPLETED`: job finalizado com sucesso.
- `FAILED`: job finalizado com erro.
- `CANCELLED`: job cancelado.

## SPI para projetos consumidores

Cada rotina assincrona deve ser exposta como um bean Spring que implementa `JobHandler<P, R>`:

```java
@Component
public class RelatorioJobHandler implements JobHandler<RelatorioRequest, RelatorioResponse> {

    @Override
    public String type() {
        return "relatorio";
    }

    @Override
    public RelatorioResponse handle(RelatorioRequest input) {
        // regra de negocio do projeto consumidor
        return new RelatorioResponse("relatorio-gerado.pdf");
    }
}
```

O building block usa o `type` do request para localizar o handler. O payload JSON, recebido como `Map`, e convertido automaticamente para o tipo generico `P` declarado no handler usando o `ObjectMapper` configurado pelo Spring Boot.

Regras importantes:

- Cada `type` deve ser unico.
- Se houver dois handlers com o mesmo `type`, a aplicacao falha no startup.
- Se o request usar um `type` sem handler registrado, a API retorna `400 Bad Request`.
- O retorno `R` do handler vira o resultado consultavel em `/jobs/{id}/result`.

## Politicas implementadas

- **Polling hint**: respostas usam `Retry-After` para orientar quando o client deve consultar novamente.
- **Retencao**: jobs sao mantidos no Valkey/Redis pelo TTL `async-jobs.result-ttl` (padrao: 1 hora).
- **Single-flight opcional**: `async-jobs.coalesce-in-flight=true` permite colapsar requests equivalentes enquanto ha job ativo.
- **Idempotencia**: `Idempotency-Key` permite reutilizar o job criado para uma submissao equivalente.
- **Problem Details**: falhas de dominio sao traduzidas para `ProblemDetail`.
- **Resultado paginado**: listas retornadas pelos handlers sao expostas com `page`, `size`, `totalElements` e `totalPages`.

## Configuracao

O projeto usa as propriedades padrao do Spring Boot para conectar no Valkey/Redis e algumas propriedades proprias sob `async-jobs`.

Exemplo de `application.yaml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

async-jobs:
  storage: redis
  web:
    enabled: true
  result-ttl: PT1H
  retry-after-seconds: 5
  coalesce-in-flight: false
  coalesce-key: type
```

Parametros proprios:

| Propriedade | Padrao | Descricao |
| --- | --- | --- |
| `async-jobs.storage` | `redis` | Seleciona a auto-configuracao de storage. Com `redis`, a lib registra os adapters Valkey/Redis via Redisson. Para storage proprio, use outro valor e registre beans `JobRepositoryPortOut` e `SingleFlightPortOut` no projeto consumidor. |
| `async-jobs.web.enabled` | `true` | Liga/desliga o adapter web servlet (`/jobs`). Quando `false`, a lib funciona apenas como motor/use cases, sem expor endpoints HTTP. |
| `async-jobs.result-ttl` | `PT1H` | Tempo de retencao dos jobs, resultados, chaves de idempotencia e controle single-flight no Valkey/Redis. Tambem e usado para calcular o header `Expires` a partir da ultima atualizacao do job. Aceita formato `Duration` do Spring, como `PT10M`, `PT1H` ou `P1D`. |
| `async-jobs.retry-after-seconds` | `5` | Hint enviado no header `Retry-After` em submissao e consulta de status enquanto o job esta ativo. Orienta o client sobre quantos segundos esperar antes do proximo polling. |
| `async-jobs.coalesce-in-flight` | `false` | Quando `true`, chamadas equivalentes enquanto um job ainda esta ativo reutilizam o mesmo job em andamento em vez de criar outro. |
| `async-jobs.coalesce-key` | `type` | Define como calcular a chave de equivalencia do single-flight. Use `type` para agrupar apenas pelo tipo do job ou `payload` para agrupar por tipo + payload normalizado. So tem efeito quando `coalesce-in-flight=true`. |

Esses hints sao centralizados em `JobPolicyPortOut`. A implementacao default (`DefaultJobPolicyAdapterOut`) evita espalhar no core ou no controller decisoes como intervalo sugerido de polling e data de expiracao do recurso.

Se o projeto consumidor precisar de uma politica propria, basta registrar um bean `JobPolicyPortOut`. A auto-configuracao so cria a policy default quando nao existe outro bean desse tipo.

Parametros de infraestrutura mais comuns:

| Propriedade | Exemplo | Descricao |
| --- | --- | --- |
| `spring.data.redis.host` | `localhost` | Host do Valkey/Redis usado pelo Redisson via auto-configuracao do Spring Boot. |
| `spring.data.redis.port` | `6379` | Porta do Valkey/Redis. |
| `spring.data.redis.password` | `secret` | Senha, quando o servidor exigir autenticacao. |
| `spring.data.redis.database` | `0` | Database logico Redis/Valkey. |

## Como executar

Requisitos:

- Java 25
- Maven Wrapper incluso no projeto

Execute os testes:

```bash
./mvnw test
```

A suite usa Testcontainers para subir Valkey automaticamente; nao e necessario manter Redis/Valkey rodando em `localhost`.

Suba a aplicacao:

```bash
./mvnw spring-boot:run
```

Submeta um job:

```bash
curl -i -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: exemplo-1" \
  -d '{"type":"relatorio","payload":{"mes":"junho","ano":2026}}'
```

Para esse exemplo funcionar em runtime, precisa existir um bean `JobHandler` cujo `type()` retorne `relatorio`.

## Cobertura atual

A suite de testes registra handlers de exemplo e cobre:

- submissao com `202 Accepted`, `Location` e `Retry-After`;
- rejeicao de `type` desconhecido com `400 Bad Request`;
- idempotencia via `Idempotency-Key`;
- consulta de status com `Retry-After` e `Expires`;
- redirecionamento `303 See Other` para o resultado quando concluido;
- resultado paginado;
- cancelamento;
- `404 Not Found` para job inexistente.

Ultima verificacao local:

```bash
./mvnw test
```

Resultado: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`.
