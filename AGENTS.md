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
acompanhamento, o processamento roda em background, o cliente acompanha por
polling (`GET /jobs/{id}/status`) ou por SSE (`GET /jobs/{id}/events`) e busca o
resultado paginado. Estado em Valkey/Redis via Redisson.

Decisões de arquitetura registradas em `docs/adr/`. Leia-as antes de mudar
contrato ou modelo de dados.

## Comandos

Rode **de dentro do WSL** — o git do Windows falha neste caminho com
"dubious ownership", e o wrapper do Maven precisa do ambiente Linux:

```bash
wsl -d Ubuntu bash -lc "cd /home/john/Code/async-request-reply && ./mvnw clean test"
```

- Suíte completa: `./mvnw clean test` (Testcontainers sobe o Valkey; não precisa
  de Redis local).
- Uma classe: `./mvnw test -Dtest=NomeDaClasse`.
- Compilar sem testar: `./mvnw test-compile`.

Use `clean` quando adicionar/renomear testes: o mount do WSL às vezes engana a
compilação incremental e a classe nova não é reconhecida.

## Arquitetura (invariantes que não devem ser quebrados)

Hexagonal, com dependências sempre apontando para dentro:

- `core/domain` — `Job`, `JobFailure`. Sem imports de framework, sem exceção
  usada como dado.
- `core/usecase`, `core/service` — orquestração. `core/service/JobTransitionService`
  é o **ponto único** de toda transição de estado.
- `core/port/in`, `core/port/out` — contratos por capacidade, não por tecnologia.
- `core/event` — `JobEvent`, publicado a cada transição.
- `adapter/in/web` (+ `sse/`), `adapter/out/{persistence/redis,processing,policy,coalescing,recovery}`.
- `config` — `AsyncJobsProperties` em pacote neutro (adapters não dependem de `autoconfigure`).
- `autoconfigure` — composition root; 4 auto-configurations em
  `src/main/resources/META-INF/spring/*.imports`.
- `spi` — o que o projeto consumidor implementa (`JobHandler`, `AsyncJobHandler`,
  `JobReporter`).

Regras que já custaram bugs e devem ser preservadas:

1. **Toda transição passa pelo `JobTransitionService`.** Ele garante os três
   efeitos juntos: check-and-set atômico → publicar evento → liberar o guard de
   single-flight. Nunca chame `repository.complete/fail/cancel` direto.
2. **Evento só depois de transição bem-sucedida**, e com o instante que foi
   **persistido** (as transições devolvem `Optional<Instant>`), nunca um
   `Instant.now()` novo.
3. **O claim do single-flight só acontece depois de persistir o job**, dentro do
   lock da chave. O guard nunca pode apontar para job inexistente.
4. **Escritas no Redis que envolvem TTL ou índice vão num único `RBatch`
   atômico** (`REDIS_WRITE_ATOMIC`). Um `expire` separado pode ficar órfão e
   vazar chave para sempre.
5. **Tempo vem do `Clock` injetado**, nunca de `Instant.now()` espalhado.
6. **O `type` compõe nome de chave no Redis e rota HTTP** — é validado no
   `JobHandlerRegistry` (startup) e na borda web.
7. Um job `FAILED` **sempre** tem `JobFailure` não-nulo (título em branco é
   normalizado), senão a renderização do Problem Detail quebra.

## Testes

- Unitários com fakes/Mockito para use cases e services (rápidos, sem contexto
  Spring). Nome no padrão `metodo_should_comportamento_when_cenario`.
- Integração com `@SpringBootTest` + Testcontainers, marcados `@Tag("integration")`.
- E2E pela borda HTTP real em `TomcatEndToEndIntegrationTest` (Tomcat em porta
  aleatória, `HttpClient` do JDK com `Redirect.NEVER`).
- `TestBootApplication` existe porque a lib não tem `@SpringBootApplication`;
  sem ela os `@SpringBootTest` não acham configuração.
- O container Valkey é **singleton compartilhado** entre classes de teste
  (Testcontainers 2.x não tem o módulo junit-jupiter). Não presuma banco limpo.
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
