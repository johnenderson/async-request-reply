# AGENTS.md

Instruções para agentes (Claude Code, Codex, Gemini, Antigravity, Pi) trabalhando
neste repositório. É a fonte única — `CLAUDE.md` apenas importa este arquivo.

## Skills

As skills deste projeto ficam em `.agents/skills/<nome>/SKILL.md` (versionadas em
`skills-lock.json`) e estão espelhadas em `.claude/skills/` para o Claude Code
descobri-las nativamente.

**Antes de qualquer resposta ou ação, leia `.agents/skills/using-superpowers/SKILL.md`
e siga a regra dela**: se houver 1% de chance de uma skill se aplicar à tarefa,
invoque-a. Skills de processo (`brainstorming`, `systematic-debugging`,
`test-driven-development`) vêm antes das de implementação.

Mais usadas aqui: `hexagonal-architecture`, `java-springboot`, `java-junit`,
`java-maven`, `test-driven-development`, `systematic-debugging`,
`verification-before-completion`, `finishing-a-development-branch`.

## O que é este projeto

Biblioteca (não aplicação) Java 25 / Spring Boot 4.1 que encapsula o padrão
**Asynchronous Request-Reply**: `POST /jobs/{type}` responde `202` com URL de
acompanhamento, o processamento roda em background e o cliente acompanha por SSE
(`GET /jobs/{id}/events`) ou por polling (`GET /jobs/{id}/status`).

**Plano de controle, não plano de dados** (ADR 0004): a lib diz *quando* a carga
terminou; o dado é lido pelo consumidor no endpoint de domínio dele. Não existe
`GET /jobs/{id}/result`, nem redirect na conclusão. Estado em uma tabela
PostgreSQL, sobre o `DataSource` da própria aplicação consumidora.

Decisões de arquitetura registradas em `docs/adr/`. Leia-as antes de mudar
contrato ou modelo de dados.

## Comandos

Rode **de dentro do WSL** — o git do Windows falha neste caminho com
"dubious ownership", e o wrapper do Maven precisa do ambiente Linux:

```bash
wsl -d Ubuntu bash -lc "cd /home/john/Code/async-request-reply && ./mvnw clean test"
```

- Suíte completa: `./mvnw clean test` (Testcontainers sobe o PostgreSQL; não
  precisa de banco local).
- Uma classe: `./mvnw test -Dtest=NomeDaClasse`.
- Compilar sem testar: `./mvnw test-compile`.

Use `clean` quando adicionar/renomear testes: o mount do WSL às vezes engana a
compilação incremental e a classe nova não é reconhecida.

## Arquitetura (invariantes que não devem ser quebrados)

Hexagonal, com dependências sempre apontando para dentro:

- `core/domain` — `Job`, `JobFailure`. Sem imports de framework, sem exceção
  usada como dado.
- `core/usecase`, `core/service` — orquestração.
- `core/port/in`, `core/port/out` — contratos por capacidade, não por tecnologia.
- `core/event` — `JobEvent`, derivado do estado persistido.
- `adapter/in/web` (+ `sse/`),
  `adapter/out/{persistence/jdbc,events,processing,policy,coalescing,recovery}`.
- `config` — `AsyncJobsProperties` em pacote neutro (adapters não dependem de `autoconfigure`).
- `autoconfigure` — composition root; 4 auto-configurations em
  `src/main/resources/META-INF/spring/*.imports`.
- `spi` — a fronteira com o projeto consumidor: ele **implementa** `JobHandler` /
  `AsyncJobHandler`, e **injeta** `JobReporter` e `JobFreshness`.

Regras que já custaram bugs e devem ser preservadas:

1. **Transição é `UPDATE ... WHERE status IN (...) RETURNING last_updated_at`** —
   check-and-set num único statement, sem leitura prévia e sem lock. O
   `Optional<Instant>` vazio significa "recusada" (job inexistente ou já
   terminal), e é isso que impede um `complete` atrasado de sobrescrever um
   cancelamento.
2. **Concorrência é resolvida por constraint, não por código.** Idempotência e
   single-flight são índices únicos parciais; o `create` insere com
   `ON CONFLICT DO NOTHING` e, ao ser recusado, devolve o job dono da chave.
   Nunca reintroduza "consulta e então cria".
3. **Eventos carregam o instante persistido**, nunca um `Instant.now()` novo — é
   o que permite ao cliente ordenar snapshot × evento numa corrida.
4. **Tempo vem do `Clock` injetado**, nunca de `Instant.now()` espalhado.
5. **O `type` compõe chave de coalescing e rota HTTP** — é validado no
   `JobHandlerRegistry` (startup) e na borda web.
6. Um job `FAILED` **sempre** tem `JobFailure` não-nulo (título em branco é
   normalizado), senão a renderização do Problem Detail quebra.
7. **O id chega do path e pode ser qualquer string.** O repositório trata id que
   não é UUID como job inexistente; propagar `IllegalArgumentException` viraria
   `500` no lugar do `404` devido.
8. **A lib não configura `DataSource` nem pool**, e não aplica DDL. O esquema é
   do consumidor; `src/main/resources/async-jobs-schema.sql` é a referência, e é
   o mesmo arquivo que os testes aplicam.
9. **Cancelar não interrompe a thread da rotina.** É cooperativo por decisão
   (ADR 0004): abortar no meio deixaria a base do consumidor parcialmente
   atualizada. Não introduza `Thread.interrupt()` nem `Future.cancel(true)`.
10. **Frescor depende de `coalesce-in-flight`**, porque a janela procura a última
    carga concluída pela `coalescing_key` — que só é gravada quando o coalescing
    está ligado. Ligar só o frescor era um no-op silencioso; hoje falha o startup.

## Testes

- Unitários com fakes/Mockito para use cases e services (rápidos, sem contexto
  Spring). Nome no padrão `metodo_should_comportamento_when_cenario`.
- Integração com `@SpringBootTest` + Testcontainers, marcados `@Tag("integration")`.
- E2E pela borda HTTP real em `TomcatEndToEndIntegrationTest` (Tomcat em porta
  aleatória, `HttpClient` do JDK com `Redirect.NEVER`).
- `TestBootApplication` existe porque a lib não tem `@SpringBootApplication`;
  sem ela os `@SpringBootTest` não acham configuração. Ela também faz o papel do
  consumidor fornecendo o `DataSource` — a lib não fornece.
- O container PostgreSQL é **singleton compartilhado** entre classes de teste
  (Testcontainers 2.x não tem o módulo junit-jupiter, então o `GenericContainer`
  sobe num bloco estático). Não presuma banco limpo: chame `truncateJobs()` no
  `@BeforeEach`.
- Handlers "lentos" de teste usam `TestGate`: timeout é falha explícita, nunca
  conclusão silenciosa — um handler que completa por timeout corrompe asserções
  de cancelamento de forma difícil de diagnosticar.

Ambiente: já foram observadas execuções da suíte travando ~250s por I/O do
WSL/Testcontainers e depois voltando a ~6s sem mudança de código. Antes de
investigar lentidão, rode a classe isolada para confirmar se é o ambiente.

## Estilo

- Javadoc e comentários em português, explicando **por quê** (a regra ou o bug
  que a linha evita), não o que o código já diz.
- Mensagens de commit em português, no imperativo, com corpo explicando a
  motivação. Sem acentos no README e nas mensagens de commit; com acentos no
  Javadoc.
- Injeção por construtor, campos `private final`.
- SLF4J com mensagens parametrizadas. Nada de `catch` silencioso: se engolir uma
  exceção, registre em `debug` (esperado) ou `warn` (anômalo).
