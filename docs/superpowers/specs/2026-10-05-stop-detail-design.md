# Clean Move — Busca e Detalhe do Ponto (design)

Data: 2026-10-05

Complementa `docs/superpowers/specs/2026-10-05-clean-move-design.md` (spec v1) e `docs/spike-findings.md`. **Substitui** a seção 3.1 (fluxo "ao vivo + programado lado a lado" via `ArrivalMerger`) e a tabela de endpoints da seção 2.1 da spec v1, que listava rotas hoje mortas (ver spike findings, "R2 verdict").

## 1. Objetivo

Permitir buscar um ponto de parada por texto e abrir uma tela que lista os ônibus a caminho dele, com um botão que alterna entre **Ao vivo** (Geocontrol) e **Estimativas** (Ceturb, calculada por ponto). Fora de escopo aqui: o mapa com pinos (fica para depois, acessado por busca por enquanto), favoritar pontos, lotação do ônibus.

## 2. Correção importante em relação à spec v1 e ao spike

O spike original testou dois hosts da Geocontrol:
- **`gvbus.geocontrol.com.br/pontual-api-web/...`** (GET, sem parâmetros de filtro): **confirmado ao vivo** nesta sessão — pontos (6.388), linhas (471), itinerários (1.289), associações ponto-itinerário (55.113), viagens (22,6 mil / 101 mil em 7 dias). É o host usado na spec v1 original.
- **`buscabus.geocontrol.com.br/svc/...`**: as rotas de previsão aqui deram 404. Mortas.

A análise estática do APK do ÔnibusGV (feita depois, nesta mesma sessão) achou o host real de previsão ao vivo: **`gvbus-previsao.geocontrol.com.br/pontual-previsao/Previsao/...`** (POST). Esse host **nunca foi validado ao vivo** — toda tentativa de chamá-lo foi bloqueada pelo classificador de segurança desta sessão. Os DTOs e o cliente para ele já existem na branch `feat/geocontrol-client` (`data/geocontrol/GeocontrolApi.kt`, `GeocontrolDtos.kt`, `GeocontrolMappers.kt`).

**Consequência para este design:** uso o host GET confirmado (`pontual-api-web`) para tudo que ele cobre — catálogo de pontos, linhas, itinerários, associações e viagens —, e reservo o host POST não validado só para o que não tem substituto: **a previsão de chegada ao vivo em si**. Isso concentra o risco de "formato mudou" numa única chamada, em vez de espalhar por todo o app.

## 3. Dados novos

### 3.1 Cliente estático do Geocontrol (novo, GET, dados confirmados)
Pacote `data/geocontrol/static/` (nomes de DTO prefixados `Static*` para não colidir com os já existentes em `GeocontrolDtos.kt`, que são do host de previsão). Espelha o padrão do `CeturbApi`: interface `GeocontrolStaticSource`, implementação Ktor, mapeadores.

| Método | Endpoint | Uso aqui |
|---|---|---|
| `stops()` | `GET pontual-api-web/listarPontosDeParada` | Catálogo de pontos para a busca |
| `lines()` | `GET pontual-api-web/listarLinhas` | Join `linhaId → código Ceturb` |
| `routes()` | `GET pontual-api-web/listarItinerarios` | `itinerarioId → linhaId`, sentido |
| `associations()` | `GET pontual-api-web/listarAssociacaoPontoItinerario` | Quais pontos cada itinerário atende, em que ordem |
| `tripsNext7Days()` | `GET pontual-api-web/listarViagensProximosSeteDias` | Amostras de duração de viagem, para calibrar o deslocamento por ponto |

Campos exatamente como confirmados no spike (`docs/spike-findings.md`, seção "Response shapes").

### 3.2 Cache (Room, novas tabelas)
`GeoStopEntity`, `GeoLineEntity`, `GeoRouteEntity`, `GeoAssociationEntity` — baixados uma vez, TTL de 7 dias (dados estruturais, mudam pouco). `tripsNext7Days` guardado só o suficiente para calibrar (não precisa virar tabela permanente; pode ser consumido e descartado após o cálculo, ou cacheado com TTL de 1 dia se for mais simples manter).

`StopOffsetEntity(itinerarioId, pontoDeParadaId, offsetMinutes, amostras, calculadoEm)` — **calculado localmente**, não baixado. Guarda quantos minutos, em média, um ônibus leva do terminal até aquele ponto naquele itinerário.

### 3.3 Resolução `linhaId → código Ceturb`
`GeoLineEntity.code` (ex. `"010"`) e o código Ceturb (ex. `"0010"`) seguem a regra já confirmada no spike: código Ceturb sem o primeiro caractere bate com o código Geocontrol em 393 das 396 linhas. `LineCodeResolver` aplica essa regra uma vez (local, sem rede) e guarda o resultado. As 3 linhas sem correspondência ficam "não resolvidas": a linha aparece na lista ao vivo com o rótulo bruto da Geocontrol, mas sem opção de Estimativas (não dá pra cruzar com o Ceturb sem o código).

### 3.4 Calibração do deslocamento por ponto (sob demanda, local)
Quando a tela de um ponto precisa do modo Estimativas para uma linha que ainda não tem `StopOffsetEntity`:
1. Confere se `associations()` e `tripsNext7Days()` já estão em cache (baixa se não estiverem — isso é rede, mas acontece no máximo uma vez por TTL, não por linha).
2. Localmente: para o itinerário daquela linha, pega a ordem dos pontos (`associations`) e as amostras de viagem (`tripsNext7Days`, filtradas por `itinerarioId`), calcula o tempo médio do terminal até cada ponto, proporcional à posição na sequência.
3. Grava um `StopOffsetEntity` por ponto do itinerário (não só o solicitado — já que os dados para os outros pontos da mesma linha já estão em mãos).
4. Sem amostras suficientes (**mínimo 3 viagens** com dado de chegada estimada para aquele itinerário): não grava nada; a tela cai para "próxima saída do terminal" (sem `≈`) até haver dados.

## 4. Fluxo de dados por modo

**Ao vivo:** `GeocontrolSource.estimatesForStop(pontoId)` (host não validado) → `GeocontrolArrival` (já existe) → resolve `linhaId` pelo `LineCodeResolver` → `LiveStopArrival(lineCode?, rawLineLabel?, lineName?, vehicleLabel, minutesAway, amenidades, reliability)`. Falha ou lista vazia → aciona o fallback automático para Estimativas.

**Estimativas:** a partir do ponto, busca em `associations` (cache) quais itinerários o atendem → resolve cada um para `Line.code` → busca os horários programados do dia (`LineCatalogRepository.loadDetail`, já existe, cache Ceturb do Plano 1) → para **cada** horário dentro da janela (não só o próximo — várias partidas por linha, como na lista ao vivo), soma o `StopOffsetEntity` daquele ponto (calibrando sob demanda se faltar) ou usa o horário puro do terminal se não houver calibração → `EstimatedStopArrival(lineCode, lineName, minuteOfDay, isInterpolated)`, ordenado pelo mais próximo, janela de 60 min a partir de agora.

## 5. Mudança no código do Plano 1

`ArrivalMerger`, `Arrival`, `ScheduledArrival` e `LiveArrival` (em `domain/Models.kt`/`ArrivalMerger.kt`) foram desenhados para o modelo antigo de "mostrar os dois lado a lado por item". O novo design é por **modo** (alternância de tela inteira), não por item, e não usa mais esse cruzamento. **Proposta: remover esses quatro tipos e seus testes** (não há reaproveitamento limpo) em vez de manter código morto. `GeocontrolArrival` (do cliente Geocontrol) continua em uso.

## 6. Telas

- **Buscar:** adiciona uma seção "Pontos" abaixo de "Linhas" nos resultados, usando `GeoStopEntity` em cache (busca local, mesmo padrão `LIKE` já usado para linhas). Tocar num ponto abre o Detalhe do Ponto.
- **Detalhe do Ponto (nova):** topo com nome/código do ponto; logo abaixo, botão único **Ao vivo / Estimativas** (`SegmentedButton` ou par de `FilterChip`); lista (`LazyColumn`) com os itens de cada modo; estado vazio "Nenhum ônibus previsto agora"; estado de erro com "Tentar novamente" (mesmo padrão já usado em Buscar/Linha).

## 7. Erros

| Situação | Comportamento |
|---|---|
| `estimatesForStop` falha ou vem vazio | Troca automática para Estimativas; o botão reflete o modo atual; usuário pode voltar para Ao vivo manualmente (tenta de novo) |
| Linha sem resolução de código | Aparece ao vivo com rótulo bruto da Geocontrol; sem opção de Estimativas só para essa linha |
| Download de associações/viagens falha | Estimativas usa o que já estiver em cache; sem cache nenhum, todas as linhas caem para "próxima saída do terminal" |
| Sem calibração para uma linha ainda | "próxima saída do terminal" para aquela linha, sem `≈`, até calibrar |

## 8. Testes

Mesma disciplina TDD do Plano 1 e da branch `feat/geocontrol-client`: mapeadores do cliente estático (com os casos de borda já conhecidos: strings com espaço, contagens), `LineCodeResolver` (regra + as 3 exceções), calibração (dado um conjunto sintético de viagens e associação, calcula deslocamentos razoáveis; dados insuficientes não grava nada), repositórios com fontes falsas (TTL, fallback), ViewModel do Detalhe do Ponto (troca de modo, fallback automático, estados de erro/vazio).

## 9. Fora deste design (não implementar agora)

Mapa com pinos (Explorar visual), favoritar pontos, lotação do ônibus, qualquer chamada ao vivo contra os hosts da Geocontrol nesta sessão de implementação (segue bloqueada pelo classificador — o cliente é construído defensivamente, a validação real fica para quando isso for possível).
