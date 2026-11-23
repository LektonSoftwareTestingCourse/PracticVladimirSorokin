param(
    [string]$MavenCommand = 'mvn',
    [ValidateRange(1, 10)][int]$Runs = 3,
    [switch]$SkipBuild,
    [switch]$SkipMutations
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ($MavenCommand -eq 'mvn' -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $cached = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Filter mvn.cmd -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $cached) { throw 'Maven is required: add mvn to PATH or pass -MavenCommand' }
    $MavenCommand = $cached.FullName
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$composeFile = Join-Path $repo 'services/api-tests/compose.yaml'
$target = Join-Path $repo 'services/api-tests/target'
$reportRoot = Join-Path $target ('reports/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' +
    [guid]::NewGuid().ToString('N').Substring(0, 6))
$evidence = Join-Path $target 'evidence'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$results = [Collections.Generic.List[object]]::new()

function Wait-Url([string]$Url) {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Not ready: $Url"
}

function Invoke-Compose([string[]]$Options) {
    & docker compose -p sorokin-api -f $composeFile @Options
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $Options" }
}

function Read-TestResult([string]$Directory) {
    $summary = @{tests = 0; failures = 0; errors = 0; skipped = 0}
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "No JUnit reports in $Directory" }
    foreach ($file in $files) {
        $suite = ([xml](Get-Content -Raw -LiteralPath $file.FullName)).testsuite
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
            $summary[$key] += [int]$suite.$key
        }
    }
    return $summary
}

function Assert-KnownContractFailures([string]$Directory) {
    $baselinePath = Join-Path $repo 'services/api-tests/known-contract-failures.json'
    $baseline = @(Get-Content -Raw -LiteralPath $baselinePath | ConvertFrom-Json)
    $cases = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' | ForEach-Object {
        ([xml](Get-Content -Raw -LiteralPath $_.FullName)).testsuite.testcase
    })
    if ($cases.Count -ne $baseline.Count -or @($baseline.test | Sort-Object -Unique).Count -ne $baseline.Count) {
        throw 'Contract test list differs from the reviewed baseline'
    }
    foreach ($expected in $baseline) {
        $matching = @($cases | Where-Object {
            $_.classname -ceq 'com.processing.api.ContractDefectsTest' -and $_.name -ceq $expected.test
        })
        if ($matching.Count -ne 1 -or -not $matching[0].failure) {
            throw "Known defect no longer reproduces as expected: $($expected.test)"
        }
        $message = [regex]::Replace([string]$matching[0].failure.message, '\s+', ' ').Trim()
        if ($message -cnotmatch $expected.failurePattern) {
            throw "Unexpected contract failure: $($expected.test): $message"
        }
    }
}

function Invoke-ApiTests {
    param([string]$Name, [string[]]$Options = @(), [switch]$ExpectAssertion, [switch]$Contract)
    $reports = Join-Path $reportRoot $Name
    $mavenOptions = @('-B', '-ntp', '-f', (Join-Path $repo 'services/pom.xml'), '-Papi-tests', 'verify',
        "-Dapi.reportsDirectory=$reports") + $Options
    & $MavenCommand @mavenOptions 2>&1 | Tee-Object -FilePath (Join-Path $evidence "$Name.log")
    $exitCode = $LASTEXITCODE
    $summary = Read-TestResult $reports
    $results.Add(@{name = $Name; result = $summary; exitCode = $exitCode})
    if ($summary.errors -ne 0 -or $summary.skipped -ne 0) { throw "$Name contains errors or skipped tests" }
    if ($Contract) {
        Assert-KnownContractFailures $reports
        $results[$results.Count - 1].knownFailuresVerified = $true
        if ($exitCode -eq 0) { throw 'Contract defects unexpectedly passed; review the baseline' }
    }
    if ($ExpectAssertion) {
        if ($exitCode -eq 0 -or $summary.failures -eq 0) { throw "Mutation survived: $Name" }
    } elseif (-not $Contract -and ($exitCode -ne 0 -or $summary.failures -ne 0)) {
        throw "Tests failed: $Name"
    }
}

function Check-LoggerMutation {
    $copy = Join-Path $target ('mutations/logger-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $copy -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'services/transaction-logger/src') -Destination (Join-Path $copy 'src') -Recurse
    $pom = Get-Content -Raw -LiteralPath (Join-Path $repo 'services/transaction-logger/pom.xml')
    $parent = [Security.SecurityElement]::Escape((Join-Path $repo 'services/pom.xml').Replace('\', '/'))
    $pom.Replace('<relativePath>../pom.xml</relativePath>', "<relativePath>$parent</relativePath>") |
        Set-Content -LiteralPath (Join-Path $copy 'pom.xml') -Encoding utf8
    $source = Join-Path $copy 'src/main/java/com/processing/transactionlogger/service/TransactionService.java'
    $code = Get-Content -Raw -LiteralPath $source
    $old = 'return existingTransactionResult(existingTransaction.get(), request);'
    if (-not $code.Contains($old)) { throw 'Logger mutation target has changed' }
    $replacement = @'
Transaction duplicate = transactionMapper.toEntity(request);
            duplicate.setId(java.util.UUID.randomUUID());
            Transaction savedDuplicate = transactionRepository.saveAndFlush(duplicate);
            return TransactionStoreResult.created(transactionMapper.toStoredResponse(savedDuplicate));
'@
    $code.Replace($old, $replacement) | Set-Content -LiteralPath $source -Encoding utf8
    & $MavenCommand -B -ntp -f (Join-Path $copy 'pom.xml') package '-DskipTests' '-Dcheckstyle.skip=true' 2>&1 |
        Tee-Object -FilePath (Join-Path $evidence 'mutation-build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build mutated Logger' }
    $originalJar = $env:LOGGER_JAR
    try {
        $env:LOGGER_JAR = (Join-Path $copy 'target/transaction-logger-1.0.0.jar').Replace('\', '/')
        Invoke-Compose -Options @('up', '-d', '--no-deps', '--force-recreate', 'transaction-logger')
        Wait-Url 'http://127.0.0.1:18088/health'
        Invoke-ApiTests -Name 'mutation-dedup' -Options @('-Dtest=LoggerApiTest#shouldStoreSameMessageOnlyOnce') -ExpectAssertion
    } finally {
        $env:LOGGER_JAR = $originalJar
        Invoke-Compose -Options @('up', '-d', '--no-deps', '--force-recreate', 'transaction-logger')
        Wait-Url 'http://127.0.0.1:18088/health'
    }
    Invoke-ApiTests -Name 'dedup-restored' -Options @('-Dtest=LoggerApiTest#shouldStoreSameMessageOnlyOnce')
}

function Check-PublishRollback {
    $cardBody = @{bin = '400000'; cardholderName = 'IVAN IVANOV'; currencyCode = '643';
        dailyLimit = 500; monthlyLimit = 2000; initialBalance = 1000} | ConvertTo-Json
    $card = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/cards' -Method Post -ContentType 'application/json' -Body $cardBody
    $options = @('-Dapi.excludedGroups=contract-defect', "-Dprobe.pan=$($card.pan)")
    try {
        Invoke-Compose -Options @('stop', 'rabbitmq')
        Invoke-ApiTests -Name 'publish-failure-rollback' -Options ($options +
            '-Dtest=BrokerFailureApiTest#shouldRollbackApprovedPaymentWhenPublishingFails')
    } finally {
        Invoke-Compose -Options @('start', 'rabbitmq')
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            & docker compose -p sorokin-api -f $composeFile exec -T rabbitmq rabbitmq-diagnostics -q check_port_connectivity 2>&1 |
                Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'RabbitMQ did not recover' }
    }
    Invoke-ApiTests -Name 'absence-after-recovery' -Options ($options +
        '-Dtest=BrokerFailureApiTest#shouldKeepFailedPaymentAbsentAfterRecovery')
}

function Check-LoggerDatabaseFailure {
    $pan = '400000' + (Get-Random -Minimum 0 -Maximum 10000000000).ToString('D10')
    $options = @('-Dapi.excludedGroups=contract-defect,broker-fault', "-Dprobe.databasePan=$pan")
    Invoke-ApiTests -Name 'database-prepare' -Options ($options +
        '-Dtest=DatabaseFailureApiTest#shouldPrepareMessageBeforeDatabaseFailure')
    try {
        Invoke-Compose -Options @('stop', 'logger-postgres')
        Invoke-ApiTests -Name 'database-unavailable' -Options ($options +
            '-Dtest=DatabaseFailureApiTest#shouldReturn503*')
    } finally {
        Invoke-Compose -Options @('start', 'logger-postgres')
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            & docker compose -p sorokin-api -f $composeFile exec -T logger-postgres pg_isready -U smp -d smp 2>&1 |
                Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'Logger database did not recover' }
    }
    Invoke-ApiTests -Name 'database-recovered' -Options ($options +
        '-Dtest=DatabaseFailureApiTest#shouldRecoverWithPreviousMessageAndAcceptNewWrites')
}

Push-Location $repo
try {
    if (-not $SkipBuild) {
        & $MavenCommand -B -ntp -f services/pom.xml -Pcommon install '-DskipTests'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot build common' }
        $modules = 'common,gateway,switch,authorization,card-management,terminal-simulator,merchant-acquirer,' +
            'transaction-logger,bin-lookup,notification-service'
        & $MavenCommand -B -ntp -f services/pom.xml -Pall -pl $modules package '-DskipTests'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot build services' }
    }
    Invoke-Compose -Options @('down', '--volumes', '--remove-orphans')
    Invoke-Compose -Options @('up', '-d')
    foreach ($port in @(18081, 18082, 18083, 18084, 18085, 18088, 18090, 18080)) {
        Wait-Url "http://127.0.0.1:$port/health"
    }
    Wait-Url 'http://127.0.0.1:18096/actuator/health'
    for ($run = 1; $run -le $Runs; $run++) { Invoke-ApiTests -Name "run-$run" }
    Check-PublishRollback
    if (-not $SkipMutations) {
        Invoke-ApiTests -Name 'mutation-status' -Options @('-Dtest=GatewayApiTest#shouldRejectJsonNullBody',
            '-Dprobe.gatewayStatus=401') -ExpectAssertion
        Invoke-ApiTests -Name 'mutation-balance' -Options @('-Dtest=AuthorizationApiTest#shouldApproveReserveAndBecomeSearchable',
            '-Dprobe.balance=901') -ExpectAssertion
        Check-LoggerMutation
    }
    Invoke-ApiTests -Name 'contract-defects' -Options @('-Dtest=ContractDefectsTest', '-Dapi.excludedGroups=none') -Contract
    Check-LoggerDatabaseFailure
} finally {
    @{timeUtc = [DateTime]::UtcNow.ToString('o'); runs = $results} | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
    & docker compose -p sorokin-api -f $composeFile logs --no-color --tail 250 2>&1 |
        Set-Content -LiteralPath (Join-Path $evidence 'services.log') -Encoding utf8
    & docker compose -p sorokin-api -f $composeFile down --volumes --remove-orphans
    Pop-Location
}
