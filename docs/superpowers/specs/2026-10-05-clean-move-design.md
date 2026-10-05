# Clean Move — Design (v1)

Data: 2026-10-05

## 1. Objetivo e contexto

App Android nativo em Kotlin, chamado **Clean Move**, para acompanhar a circulação e o tempo de chegada dos ônibus do Transcol (Grande Vitória, ES). Uso principal: deslocamento pessoal do autor. Meta secundária: publicação na Play Store.

Referência funcional: app ÔnibusGV (GVBus), com as mesmas funcionalidades base e melhorias. Diferencial central: toda previsão de chegada mostra, ao lado, o **horário programado** do site oficial, para que o usuário tenha uma estimativa quando o GVBus não tiver dado ao vivo.

### Restrições definidas
- Sem backend e sem autenticação. O app consome as fontes diretamente.
- Visual minimalista e moderno, tema escuro.
- Somente Android. Kotlin Multiplatform fora de escopo.
- Ambiente de desenvolvimento do zero (nada de Kotlin instalado).

### Fora da v1 (decisão explícita)
Alertas de chegada, planejamento de rota de A a B, tema claro, widgets, iOS.

## 2. Fontes de dados

### 2.1 Fonte principal — Geocontrol (GVBus), tempo real
Endpoints identificados no código público do projeto `NaturesProphet/prodest-api-ceturb`. **Ainda não validados ao vivo** (ver risco R2).

| Uso | Método e URL |
|---|---|
| Lista de pontos de parada | `GET https://gvbus.geocontrol.com.br/pontual-api-web/listarPontosDeParada` |
| Pontos por área | `POST https://buscabus.geocontrol.com.br/svc/json/db/pesquisarPontosDeParada` com `{"envelope":[left,top,right,bottom]}` |
| Previsões por ponto | `POST https://buscabus.geocontrol.com.br/svc/estimativas/obterEstimativasPorOrigem` com `{"pontoDeOrigemId":n}` |
| Previsões por ponto e linha | `POST .../svc/estimativas/obterEstimativasPorOrigemELinha` com `{"pontoDeOrigemId":n,"linhaId":n}` |
| Previsões por origem e destino | `POST .../svc/estimativas/obterEstimativasPorOrigemEDestino` |
| Viagens | `GET https://gvbus.geocontrol.com.br/pontual-api-web/listarViagens` |
| Associação ponto–itinerário | `GET https://gvbus.geocontrol.com.br/pontual-api-web/listarAssociacaoPontoItinerario` |

Plano B se os endpoints tiverem mudado: emular Android, instalar o app ÔnibusGV (`br.com.geocontrol.pontual_previsao_cordova`, app Cordova) e inspecionar o tráfego de rede.

### 2.2 Fonte secundária — Ceturb, horários programados (verificada)
Base: `https://api.onibus.ceturb.es.gov.br/api/` (o host antigo `sistemas.es.gov.br/webservices/ceturb/onibus/api/` redireciona para ele). JSON, sem autenticação, testado em 2026-10-05.

| Uso | Endpoint |
|---|---|
| Linhas | `ConsultaLinha/?Tipo_Linha=T` (T = Transcol) |
| Horários | `BuscaHorarios/{linha}` |
| Itinerário | `BuscaItinerarios/{linha}` |
| Observações | `BuscaHorarioObse/{linha}` |

**Limitação:** `BuscaHorarios` informa apenas a **saída no terminal de origem** (`Hora_Saida`, `Terminal_Seq`, `Descricao_Hora` = tipo de dia). Não há horário por ponto.

## 3. Arquitetura

Módulo Gradle único (`app`), pacote `com.cleanmove`, separado por pacotes:

```
com.cleanmove
├─ data/
│  ├─ geocontrol/   cliente HTTP + DTOs
│  ├─ ceturb/       cliente HTTP + DTOs
│  ├─ local/        Room (linhas, pontos, itinerários, horários) + DataStore (favoritos)
│  └─ repository/   implementações
├─ domain/          modelos, interfaces, ArrivalMerger
└─ ui/              telas Compose, ViewModels, tema
```

Padrões: ViewModels com Flow, Hilt para injeção, coroutines.

### 3.1 Fluxo da previsão
1. A tela de um ponto pede a `ArrivalRepository` as chegadas do ponto.
2. Em paralelo: `GeocontrolSource` (ao vivo) e `CeturbScheduleSource` (programado, a partir do cache Room).
3. `ArrivalMerger` cruza por linha e devolve `Previsao(aoVivo?, programado?)` por ônibus.
4. A UI mostra os dois lado a lado. Sem dado ao vivo: só o programado, com selo "sem GPS".

### 3.2 Regras
- Cada fonte implementa a interface `ArrivalSource` e falha de forma independente. Trocar endpoint ou provedor afeta um único pacote.
- **Programado no ponto é derivado:** saída no terminal (Ceturb) mais o offset do ponto na viagem (itinerário e viagens da Geocontrol). Se não for possível calcular, **não se exibe** o programado por ponto; no nível da linha mostra-se a próxima saída do terminal.
- **Cache offline:** linhas, pontos, itinerários e horários são baixados uma vez e renovados por TTL (24 h). Só as previsões vão à rede a cada consulta. Consulta de pontos por área do mapa é feita localmente no Room; a API por envelope é fallback.
- Atualização automática das previsões a cada ~20 s enquanto a tela estiver aberta.

## 4. Telas da v1

Navegação inferior com 3 abas: **Mapa** (inicial), **Buscar**, **Favoritos**. A tela **Sobre** é acessada por ícone no topo de Favoritos.

1. **Mapa:** centraliza na localização do usuário (permissão pedida só ao abrir a aba); marcadores de ponto a partir de certo zoom; toque abre o detalhe do ponto; ao ver uma linha, mostra ônibus ao vivo e o traçado.
2. **Detalhe do ponto:** bottom sheet no mapa, tela cheia ao vir da busca. Chegadas por linha ordenadas pela mais próxima: "3 min" ao vivo e "programado 14:32" ao lado. Botão de favoritar.
3. **Buscar:** campo único para linha (número ou nome) e ponto (nome ou código), sobre o cache Room, funciona offline.
4. **Detalhe da linha:** itinerário por sentido, horários por tipo de dia (dias úteis, sábado, domingos e feriados) e ver a linha no mapa.
5. **Favoritos:** pontos e linhas salvos com a próxima chegada.
6. **Sobre:** créditos a Geocontrol, GVBus e Ceturb, versão e aviso de dados não oficiais.

### Estilo
Tema escuro único: fundo quase preto, superfícies em cinzas. Um acento (verde ou ciano) para "ao vivo", âmbar discreto para "programado/sem GPS". Cantos arredondados, bastante espaço. Mapa com estilo escuro oficial do Google Maps (Maps Compose).

## 5. Tratamento de erros

| Situação | Comportamento |
|---|---|
| Geocontrol fora ou bloqueada | Só o programado, selo "sem GPS", aviso discreto |
| Ceturb fora | Só o ao vivo; cache Room cobre os horários já baixados |
| Sem internet | Último estado conhecido com hora da atualização; busca e itinerários pelo cache |
| Formato da API mudou | Parsing tolerante (`ignoreUnknownKeys`, campos opcionais); resposta inválida vira erro tipado, sem crash |
| Ponto sem chegadas | Estado vazio: "Nenhum ônibus previsto agora" |

Erros são resultado tipado na camada de dados; a UI decide a apresentação.

## 6. Testes

- **Unitários (maior cobertura):** `ArrivalMerger`, derivação do programado, parsing de DTOs com fixtures de respostas reais.
- **Dados:** repositórios com fontes falsas cobrindo a tabela de erros; Room em memória.
- **ViewModels:** estados de loading, sucesso e erro (Turbine, coroutines de teste).
- **UI:** poucos testes Compose nos fluxos principais (abrir ponto, favoritar).
- **Contrato com a API real:** teste manual/opcional, fora do CI, que detecta mudança de formato.

## 7. Ambiente e versões

- **Android Studio** via `winget` (inclui JDK embutido, SDK Manager e emulador); SDK Platform e build-tools da API estável mais recente; imagem x86_64 para o emulador (exige virtualização ativa).
- Build por linha de comando com Gradle Wrapper.
- Versões **não fixadas nesta spec**: no setup consultam-se as versões estáveis mais recentes de AGP, Kotlin, Compose BOM, Room, Hilt e Ktor, registradas em um único `libs.versions.toml`.
- Critérios fixos: Kotlin 2.x com plugin do Compose compiler; **minSdk 26**; **targetSdk** na API estável mais recente (exigência da Play Store); KSP (não kapt).
- Dependências: Compose + Material 3, Navigation Compose, Hilt, Room, DataStore, Ktor Client + kotlinx.serialization, Maps Compose + Play Services Maps/Location, coroutines. Testes: JUnit, MockK, Turbine, coroutines-test, Compose UI test.
- Projeto em `projects/clean-move/` com git; `applicationId` inicial `com.cleanmove` (permanente após o lançamento, revisar antes de publicar).
- Chave do Google Maps em `local.properties` (no `.gitignore`), restrita ao pacote e à assinatura.
- Fixtures das APIs em `app/src/test/resources/fixtures/`.

## 8. Riscos

| # | Risco | Mitigação |
|---|---|---|
| R1 | Derivar o programado por ponto (Ceturb só dá saída no terminal) | Spike como primeira tarefa; fallback para programado por linha |
| R2 | Endpoints da Geocontrol não testados ao vivo, possivelmente desatualizados | O spike valida cada endpoint e grava fixtures; plano B por inspeção de tráfego no emulador |
| R3 | **Termos de uso da Geocontrol** proíbem acesso automatizado fora da interface deles e reprodução sem autorização; a "OnibusGv API" é liberada a critério deles, com atribuição obrigatória. Risco de bloqueio ou remoção na Play Store | Decisão: construir e pedir autorização em paralelo. Fonte principal atrás de interface trocável; atribuição na tela Sobre; pedir autorização à Geocontrol e à Ceturb **antes de publicar** |
| R4 | Volume de pontos estáticos | Baixar uma vez, guardar no Room, consultar por área localmente |
| R5 | Chave do Maps exposta | Fora do repositório e restrita por pacote e assinatura |

## 9. Ordem de entrega (base para o plano)

1. Spike de dados: validar endpoints Geocontrol, gravar fixtures, confirmar derivação do programado.
2. Esqueleto do projeto, tema escuro e navegação.
3. Camada de dados: clientes, Room, repositórios e `ArrivalMerger`, com testes.
4. Telas: Mapa, detalhe do ponto, Buscar, detalhe da linha, Favoritos.
5. Acabamento: erros, estados vazios, tela Sobre, ícone.
6. Checklist de lançamento (ver abaixo).

### Checklist de lançamento (Play Store)
- Autorização formal da Geocontrol e da Ceturb para uso dos dados.
- Política de privacidade (o app usa localização) e declaração de segurança dos dados.
- Revisão do `applicationId`, assinatura do app e ficha da loja.
- Atribuição visível aos donos dos dados.
