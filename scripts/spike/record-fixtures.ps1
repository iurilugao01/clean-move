# scripts/spike/record-fixtures.ps1
param([string]$PontoId = "")
$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "..\..\app\src\test\resources\fixtures"
New-Item -ItemType Directory -Force $out | Out-Null

function Save($name, $items) {
    $json = ConvertTo-Json -InputObject @($items) -Depth 20
    Set-Content -Path (Join-Path $out $name) -Value $json -Encoding utf8
    Write-Host ("saved {0} ({1} items)" -f $name, @($items).Count)
}
function Shape($label, $items) {
    $first = @($items) | Select-Object -First 1
    Write-Host "--- $label"
    if ($first) { $first | Format-List | Out-String | Write-Host } else { Write-Host "(empty)" }
}
function TryCall($label, [scriptblock]$call) {
    try { & $call } catch { Write-Host "FAILED $label : $($_.Exception.Message)" -ForegroundColor Red; $null }
}

# --- Ceturb (secondary source)
$c = "https://api.onibus.ceturb.es.gov.br/api"
$lines = Invoke-RestMethod "$c/ConsultaLinha/?Tipo_Linha=T"
Save "ceturb_linhas.json" ($lines | Select-Object -First 40)
Save "ceturb_horarios_0010.json" (Invoke-RestMethod "$c/BuscaHorarios/0010")
Save "ceturb_horarios_001_empty.json" (Invoke-RestMethod "$c/BuscaHorarios/001")
Save "ceturb_itinerario_0010.json" (Invoke-RestMethod "$c/BuscaItinerarios/0010")

# --- Geocontrol (primary source) - endpoints from prodest-api-ceturb, unverified
$pontos = TryCall "listarPontosDeParada" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarPontosDeParada" -TimeoutSec 60 }
if ($pontos) { Shape "pontos" $pontos; Write-Host "total pontos: $(@($pontos).Count)"; Save "geo_pontos_sample.json" ($pontos | Select-Object -First 50) }

$area = TryCall "pesquisarPontosDeParada" {
    Invoke-RestMethod -Method Post -ContentType "application/json" -TimeoutSec 60 `
      -Uri "https://buscabus.geocontrol.com.br/svc/json/db/pesquisarPontosDeParada" `
      -Body '{"envelope":[-40.32,-20.28,-40.29,-20.27]}'
}
if ($area) { Shape "pontos por area" $area; Save "geo_pontos_area.json" $area }

$viagens = TryCall "listarViagens" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarViagens" -TimeoutSec 60 }
if ($viagens) { Shape "viagens" $viagens; Write-Host "total viagens: $(@($viagens).Count)"; Save "geo_viagens_sample.json" ($viagens | Select-Object -First 50) }

$assoc = TryCall "listarAssociacaoPontoItinerario" { Invoke-RestMethod "https://gvbus.geocontrol.com.br/pontual-api-web/listarAssociacaoPontoItinerario" -TimeoutSec 60 }
if ($assoc) { Shape "associacao ponto-itinerario" $assoc; Write-Host "total assoc: $(@($assoc).Count)"; Save "geo_assoc_sample.json" ($assoc | Select-Object -First 50) }

if ($PontoId) {
    $est = TryCall "obterEstimativasPorOrigem" {
        Invoke-RestMethod -Method Post -ContentType "application/json" -TimeoutSec 60 `
          -Uri "https://buscabus.geocontrol.com.br/svc/estimativas/obterEstimativasPorOrigem" `
          -Body (@{ pontoDeOrigemId = [int]$PontoId } | ConvertTo-Json)
    }
    if ($est) { Shape "estimativas ponto $PontoId" $est; Save "geo_estimativas_$PontoId.json" $est }
}
