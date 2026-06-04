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
5. Ao concluir, a API redireciona ou disponibiliza o resultado.

## Arquitetura

O projeto segue uma organizacao inspirada em arquitetura hexagonal:

- `core/domain`: modelo de dominio do job e transicoes de estado.
- `core/usecase`: casos de uso da aplicacao, sem dependencia direta de HTTP.
- `core/port/in`: portas de entrada usadas pelos adapters.
- `core/port/out`: portas de saida para persistencia, politica e processamento.
- `adapter/in/web`: controller HTTP e mapeamento de erros para Problem Details.
- `adapter/out/persistence`: persistencia in-memory dos jobs.
- `adapter/out/processing`: processamento assincrono baseado em `@Async`.
- `adapter/out/policy`: politicas padrao de polling e retencao.
- `spi`: contrato que o projeto consumidor deve implementar para plugar rotinas reais.

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

Resposta:

```http
202 Accepted
Location: /jobs/{jobId}/status
Retry-After: 5
```

```json
{
  "jobId": "uuid-do-job",
  "statusUrl": "/jobs/uuid-do-job/status"
}
```

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
  "percentComplete": 60
}
```

### Buscar resultado

```http
GET /jobs/{id}/result
```

Possiveis respostas:

- `200 OK`: resultado disponivel.
- `409 Conflict`: job ainda nao foi concluido.
- `404 Not Found`: job inexistente.

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

A intencao do building block e permitir que um projeto consumidor implemente um `JobHandler` para cada rotina assincrona:

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

O fluxo pretendido e:

1. O request informa um `type`.
2. O building block localiza o `JobHandler` correspondente.
3. O payload e convertido para o tipo esperado pelo handler.
4. O handler roda de forma assincrona.
5. O retorno do handler fica disponivel em `/jobs/{id}/result`.

## Politicas implementadas

- **Polling hint**: respostas usam `Retry-After` para orientar quando o client deve consultar novamente.
- **Retencao**: jobs sao mantidos por 1 hora na implementacao in-memory.
- **Eviction agendada**: limpeza executada a cada 15 minutos.
- **Idempotencia**: `Idempotency-Key` permite reutilizar o job criado para uma submissao equivalente.
- **Problem Details**: falhas de dominio sao traduzidas para `ProblemDetail`.

## Como executar

Requisitos:

- Java 25
- Maven Wrapper incluso no projeto

Execute:

```bash
./mvnw spring-boot:run
```

Em outro terminal, use o script de exemplo:

```bash
./test.sh
```

Ou submeta manualmente:

```bash
curl -i -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: exemplo-1" \
  -d '{"type":"relatorio","payload":{"mes":"junho","ano":2026}}'
```

## Estado atual do projeto

O desenho do core ja aponta para a versao de building block com suporte a `type` e `JobHandler`, mas o adapter in-memory e o processador assincrono ainda precisam ser alinhados a essa versao.

No estado atual, `./mvnw test` falha na compilacao por estes desalinhamentos:

- `JobRepositoryPortOut` espera `save(String type, Object payload, String idempotencyKey)`, mas `InMemoryJobRepositoryOut` ainda implementa `save(Object payload, String idempotencyKey)`.
- `Job` espera `id`, `type` e `payload`, mas o adapter in-memory ainda instancia apenas `id` e `payload`.
- `JobProcessorPortOut` define `supports(String type)`, mas `AsyncJobProcessorOut` ainda nao implementa esse metodo.
- `SubmitJobUseCase` ainda chama o reposititorio sem separar `type` e `payload`.

## Proximos passos sugeridos

- Validar que o request possui `type` e `payload`.
- Ajustar `SubmitJobUseCase` para chamar `repository.save(type, payload, idempotencyKey)`.
- Ajustar `InMemoryJobRepositoryOut` para persistir `type` e `payload`.
- Fazer `AsyncJobProcessorOut` despachar para o `JobHandler` registrado para o `type`.
- Retornar erro de validacao quando nao houver handler para o `type`.
- Adicionar testes para submissao, idempotencia, consulta de status, resultado, cancelamento e falhas.
