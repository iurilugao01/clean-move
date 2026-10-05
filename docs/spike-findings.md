# Spike findings (2026-10-05)

Evidence comes from live calls made with `scripts/spike/record-fixtures.ps1` plus ad-hoc PowerShell analysis on 2026-10-05 (~10:26 local, UTC-3). Fixtures are in `app/src/test/resources/fixtures/`.

## Endpoint status
| Endpoint | Live? | Item count | Notes |
|---|---|---|---|
| ceturb `ConsultaLinha/?Tipo_Linha=T` | OK (200) | 396 | JSON `[{Descricao, Linha}]` |
| ceturb `BuscaHorarios/{linha}` | OK (200) | 74 for `0010`, `[]` for `001` | only origin-terminal departure times |
| ceturb `BuscaItinerarios/{linha}` | OK (200) | 66 for `0010` | `Desc_Via` right-padded with spaces |
| geo `pontual-api-web/listarPontosDeParada` | OK (200) | 6388 | |
| geo `pontual-api-web/listarLinhas` | OK (200) | 471 | |
| geo `pontual-api-web/listarItinerarios` | OK (200) | 1289 | |
| geo `pontual-api-web/listarAssociacaoPontoItinerario` | OK (200) | 55113 | |
| geo `pontual-api-web/listarViagens` | OK (200) | ~22.6k | trips for roughly the next ~28 h (and the last few hours) |
| geo `pontual-api-web/listarViagensProximosSeteDias` | OK (200) | 101450 | |
| geo `buscabus.geocontrol.com.br/svc/json/db/pesquisarPontosDeParada` | **404** | - | dead |
| geo `buscabus.geocontrol.com.br/svc/estimativas/obterEstimativasPorOrigem` | **404** | - | dead (also 404 on host `gvbus.geocontrol.com.br/svc/...`) |
| geo `.../obterEstimativasPorOrigemELinha` | **404** | - | dead on `gvbus` host (not tried on `buscabus`) |

`https://gvbus.geocontrol.com.br/pontual-api-web/` serves a public index page listing exactly six methods: `listarPontosDeParada`, `listarLinhas`, `listarItinerarios`, `listarAssociacaoPontoItinerario`, `listarViagens`, `listarViagensProximosSeteDias`. All six are plain GETs returning JSON, no authentication.

## Response shapes
- **Ponto** (`listarPontosDeParada`): `id` (int), `codigo` (string, e.g. `"110001"`), `municipio`, `logradouro`, `referencia`, `longitude`/`latitude` (JSON numbers; the decimal comma seen in console output is only PowerShell's pt-BR formatting — the saved fixture shows `-40.3196039018834`, but that file was re-serialized by PowerShell, so Plan 2 should still confirm the raw wire format with `curl`), `azimute` (int), `terminal` (bool).
- **Linha** (`listarLinhas`): `id` (int), `codigo` (string), `descricao`.
- **Itinerario** (`listarItinerarios`): `id`, `codigo` (line code + direction suffix, e.g. `"010I"`), `bandeira` (destination label), `linhaId`.
- **Associação** (`listarAssociacaoPontoItinerario`): `itinerarioId`, `ordem` (stop order, 1-based), `pontoDeParadaId`, `embarque` (bool), `desembarque` (bool).
- **Viagem** (`listarViagens`): `dataAgendada` (epoch ms, scheduled departure at the first stop), `linhaId`, `itinerarioId`, `dataChegadaEstimada` (epoch ms), `veiculo` (int vehicle id, present on ~87% of trips), `acessibilidade` (bool).
- There is **no** vehicle position, no per-stop estimate and no per-stop time in any live endpoint.

## Line-code mapping
Ceturb `Linha` minus its first character equals Geocontrol `codigo` for **393 of 396** Transcol lines (Ceturb `0010` ↔ Geo `010`, `0015` ↔ `015`). Misses: `0579`, `073N`, `073S` (need an explicit alias table or name match). Geo also has lines that look like Ceturb codes but are different (Geo `0015` "Jockey de Itaparica/São Torquato", id 744, is not Ceturb `0015`). Match by `codigo` after dropping the first character, never by padding.

## R1 verdict — scheduled time at a stop
**Partial.** Evidence: for every trip, `dataChegadaEstimada − dataAgendada` is 5.2–230.6 min (mean 58 min), i.e. `dataChegadaEstimada` is the estimated arrival at the **end of the itinerary**, not at an intermediate stop. A per-stop time can only be **interpolated**: take the trip's start (`dataAgendada`) and end (`dataChegadaEstimada`) and distribute along the itinerary stops (ordered by `ordem`) proportionally to cumulative haversine distance between stop coordinates. That is an approximation, not data.
`listarViagensProximosSeteDias` (101,450 trips) gives scheduled starts per itinerary for a week, so scheduled time at a stop can use the same interpolation with the trip's *scheduled* duration, which the API does not expose directly (only the estimate does). Plan 2 must decide how to label interpolated values (e.g. "≈").

## R2 verdict — are the Geocontrol endpoints usable as-is?
**Partially.** The six `pontual-api-web` endpoints work and are publicly indexed. The two endpoints the old reference code used for real-time work (`svc/estimativas/...` and `svc/json/db/pesquisarPontosDeParada`) are **dead (404)**. So today there is **no known live source** for (a) per-stop live arrival estimates and (b) vehicle positions for the map.
What exists is trip-level data with a vehicle id and an updated end-of-trip estimate. **Plan B is now required** for true per-stop estimates and live positions: install the ÔnibusGV app (`br.com.geocontrol.pontual_previsao_cordova`, a Cordova app, so its JS bundle should be readable) in an Android emulator and inspect its network calls. This needs the user's go-ahead before starting because it is reverse engineering of the official app (the user pre-authorized it in the original brief, but it also sharpens the Geocontrol terms-of-use risk R3).

## Notes for Plan 2
- Treat `pontual-api-web` as the primary, stable, documented-by-index source: lines, stops, itineraries, associations, trips.
- Scheduled arrivals could be built entirely from Geocontrol (`listarViagensProximosSeteDias` + association + interpolation), making the Ceturb schedule feed a cross-check or fallback rather than a hard dependency; keep both behind `ArrivalSource`.
- Download sizes: associations (55k rows) and trips (22–101k rows) are large; fetch once, store in Room, refresh by TTL, never per screen open.
- Times are epoch milliseconds (UTC); convert to America/Sao_Paulo for display.
- Fixtures were written by Windows PowerShell 5.1 `-Encoding utf8`, which adds a **UTF-8 BOM**; strip it (or re-record with `curl`) before feeding them to kotlinx.serialization in tests.
- Decide with the user whether to run Plan B (emulator traffic inspection) to find live estimate and vehicle position endpoints, or ship v1 with trip-level estimates only.
