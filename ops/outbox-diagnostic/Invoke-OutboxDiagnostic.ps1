#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch] $LoginOnly,
    [switch] $ForceBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $PSScriptRoot "compose.yaml"
$composeEnvFile = Join-Path $PSScriptRoot "empty.env"
$composeProject = "friendline-outbox-diagnostic"
$homeserverName = "outbox.test"
$hostHomeserverPort = 8009
$deviceHomeserverPort = 18009
$deviceHomeserverUrl = "http://127.0.0.1:$deviceHomeserverPort"
$hostHealthUrl = "http://127.0.0.1:8009/_matrix/client/versions"
$applicationId = "dev.friendline.messenger.outboxdiag.debug"
$testApplicationId = "$applicationId.test"
$apkOutputDirectory = Join-Path $workspace "app\build\outputs\apk"

$docker = (Get-Command docker -ErrorAction Stop).Source
$adb = (Get-Command adb -ErrorAction Stop).Source

function Invoke-DockerCompose {
    param(
        [Parameter(Mandatory)] [string[]] $Arguments,
        [switch] $AllowFailure
    )

    $dockerArguments = @(
        "compose", "--project-name", $composeProject,
        "--file", $composeFile, "--env-file", $composeEnvFile
    ) + $Arguments
    $null = & $docker @dockerArguments 2>$null
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "Isolated Synapse operation failed (exit=$exitCode). Existing Synapse services were not targeted."
    }
    return $exitCode
}

function Invoke-DockerComposeWithInput {
    param(
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string[]] $InputLines
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $docker
    $startInfo.WorkingDirectory = $workspace
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in (@(
        "compose", "--project-name", $composeProject,
        "--file", $composeFile, "--env-file", $composeEnvFile
    ) + $Arguments)) {
        [void] $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Could not start the isolated account provisioning process."
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    foreach ($line in $InputLines) {
        # The Linux container's POSIX `read` must not retain a Windows CR byte.
        $process.StandardInput.Write($line)
        $process.StandardInput.Write("`n")
    }
    $process.StandardInput.Close()
    $process.WaitForExit()
    $stdoutText = $stdoutTask.GetAwaiter().GetResult()
    $stderrText = $stderrTask.GetAwaiter().GetResult()
    $exitCode = $process.ExitCode
    $process.Dispose()
    if ($exitCode -ne 0) {
        $safeFailure = [regex]::Match(
            "$stdoutText`n$stderrText",
            "OUTBOX_ACCOUNT_PROVISION_FAILURE class=([A-Za-z]+)"
        )
        $failureClass = if ($safeFailure.Success) { $safeFailure.Groups[1].Value } else { "ProvisioningCommand" }
        Write-Output "OUTBOX_DIAG_FAILURE stage=provision class=$failureClass"
        throw "Fresh diagnostic account provisioning failed; command output and credentials were suppressed."
    }
}

function Test-DiagnosticConfigExists {
    $arguments = @(
        "compose", "--project-name", $composeProject,
        "--file", $composeFile, "--env-file", $composeEnvFile,
        "run", "--rm", "--no-deps", "--entrypoint", "sh", "synapse",
        "-c", "if test -s /data/homeserver.yaml; then echo outbox-config-present; else echo outbox-config-missing; fi"
    )
    $output = & $docker @arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the isolated Synapse config volume."
    }
    return ($output -contains "outbox-config-present")
}

function Initialize-DiagnosticSynapse {
    if (-not (Test-DiagnosticConfigExists)) {
        $null = Invoke-DockerCompose -Arguments @("run", "--rm", "--no-deps", "synapse", "generate")
    }
    $null = Invoke-DockerCompose -Arguments @(
        "run", "--rm", "--no-deps", "--entrypoint", "python", "synapse",
        "/opt/outbox-diagnostic-configure.py"
    )
    $null = Invoke-DockerCompose -Arguments @("up", "-d")
    Wait-DiagnosticSynapse
}

function Test-DiagnosticSynapse {
    try {
        $null = Invoke-RestMethod -Uri $hostHealthUrl -TimeoutSec 2
        return $true
    } catch {
        return $false
    }
}

function Wait-DiagnosticSynapse {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if (Test-DiagnosticSynapse) { return }
        Start-Sleep -Seconds 1
    }
    throw "The isolated loopback Synapse instance did not become ready on port 8009."
}

function New-RandomPassword {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd([char] "=").Replace("+", "-").Replace("/", "_")
}

function Invoke-ProvisionFreshAccounts {
    param(
        [Parameter(Mandatory)] [string] $SenderLocalpart,
        [Parameter(Mandatory)] [string] $SenderPassword,
        [Parameter(Mandatory)] [string] $PeerLocalpart,
        [Parameter(Mandatory)] [string] $PeerPassword
    )

    $shell = @'
set -eu
IFS= read -r sender
IFS= read -r sender_password
IFS= read -r peer
IFS= read -r peer_password
register_user() {
  localpart="$1"
  password="$2"
  inputfile="$(mktemp /tmp/outbox-diagnostic-input.XXXXXX)"
  logfile="$(mktemp /tmp/outbox-diagnostic-result.XXXXXX)"
  trap 'rm -f "$inputfile" "$logfile"' EXIT
  printf '%s\n%s\nno\n' "$password" "$password" > "$inputfile"
  if register_new_matrix_user http://127.0.0.1:8008 --config /data/homeserver.yaml \
    --user "$localpart" < "$inputfile" >"$logfile" 2>&1; then
    rm -f "$inputfile" "$logfile"
    trap - EXIT
    return 0
  fi
  if grep -Eqi 'unrecognized arguments|no such option|invalid choice' "$logfile"; then
    failure_class=CliOptions
  elif grep -Eqi 'command not found|not found' "$logfile"; then
    failure_class=CliUnavailable
  elif grep -Eqi 'connection refused|could not connect|timed out' "$logfile"; then
    failure_class=ServerUnavailable
  elif grep -Eqi 'shared secret|registration.*disabled|unauthorized|forbidden' "$logfile"; then
    failure_class=RegistrationConfig
  elif grep -Eqi 'no such file|permission denied|cannot open' "$logfile"; then
    failure_class=RuntimeFile
  else
    exception_class="$(grep -oE '[A-Za-z_][A-Za-z0-9_]*(Exception|Error)' "$logfile" | tail -n 1 || true)"
    if [ -n "$exception_class" ]; then
      failure_class="$exception_class"
    else
      failure_class=RegistrationFailed
    fi
  fi
  printf 'OUTBOX_ACCOUNT_PROVISION_FAILURE class=%s\n' "$failure_class" >&2
  rm -f "$inputfile" "$logfile"
  trap - EXIT
  return 1
}
register_user "$sender" "$sender_password"
register_user "$peer" "$peer_password"
'@

    $input = @(
        $SenderLocalpart,
        $SenderPassword,
        $PeerLocalpart,
        $PeerPassword
    )
    Invoke-DockerComposeWithInput -Arguments @("exec", "-T", "synapse", "sh", "-c", $shell) -InputLines $input
}

function Invoke-DiagnosticInstrumentation {
    param(
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $Stage,
        [Parameter(Mandatory)] [string] $Marker,
        [string] $Username,
        [string] $Password,
        [string] $RecipientUserId,
        [switch] $LoginOnly
    )

    $arguments = @(
        "-s", $Device, "shell", "am", "instrument", "-w",
        "-e", "class", "dev.friendline.messenger.data.OfflineOutboxIntegrationTest",
        "-e", "stage", $Stage,
        "-e", "marker", $Marker
    )
    if ($Stage -eq "prepare") {
        $arguments += @(
            "-e", "homeserver_url", $deviceHomeserverUrl,
            "-e", "username", $Username,
            "-e", "password", $Password,
            "-e", "recipient_user_id", $RecipientUserId
        )
        if ($LoginOnly) {
            $arguments += @("-e", "login_only", "true")
        }
    }
    $arguments += "$testApplicationId/androidx.test.runner.AndroidJUnitRunner"

    $output = & $adb @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $safeOutput = [string]::Join([Environment]::NewLine, [string[]] $output)
    $expectedResult = [regex]::Match(
        $safeOutput,
        "OUTBOX_DIAG_RESULT stage=$([regex]::Escape($Stage)) [^\r\n]+"
    )
    if (-not $expectedResult.Success) {
        $resultStatus = [regex]::Match($safeOutput, "outbox_diag_result=([A-Za-z0-9_=|-]+)")
        if ($resultStatus.Success) {
            $resultParts = $resultStatus.Groups[1].Value -split '\|'
            $formattedResult = if ($resultParts.Count -eq 3) {
                "OUTBOX_DIAG_RESULT stage=$($resultParts[0]) $($resultParts[1]) $($resultParts[2])"
            } else {
                "OUTBOX_DIAG_RESULT invalid-result-status"
            }
            $expectedResult = [regex]::Match(
                $formattedResult,
                "OUTBOX_DIAG_RESULT stage=$([regex]::Escape($Stage)) [^\r\n]+"
            )
        }
    }
    if ($exitCode -ne 0 -or -not $expectedResult.Success) {
        $instrumentationCode = [regex]::Match($safeOutput, "INSTRUMENTATION_CODE: (-?[0-9]+)")
        $instrumentationCompletion = if ($instrumentationCode.Success) {
            "code-$($instrumentationCode.Groups[1].Value)"
        } elseif ($exitCode -eq 0 -and $expectedResult.Success) {
            "stream-result"
        } else {
            "missing"
        }
        $resultPresence = if ($expectedResult.Success) { "present" } else { "missing" }
        $boundary = if ($safeOutput -match "OUTBOX_DIAG_BOUNDARY reached=test-method|outbox_diag_boundary=test-method-entered") {
            "test-method-entered"
        } else {
            "test-method-not-reached"
        }
        $progressPattern = "OUTBOX_DIAG_PROGRESS stage=$([regex]::Escape($Stage)) step=(repository-construction|versionsProbe|minimalClientBuilder|minimalSdkLogin|repositoryLogin|awaitConnected|createEncryptedConversation|awaitRoomReady|openConversation|persistDiagnosticRoom|verifyDiagnosticRoom|restoreSession|sendText|awaitLocalEcho|selectLocalEcho|assertOfflineEcho|awaitDelivery|verifyExactlyOnce) state=(start|complete)"
        $progressMatches = [regex]::Matches($safeOutput, $progressPattern)
        $progressEvents = @(
            foreach ($progressMatch in $progressMatches) {
                "$($progressMatch.Groups[1].Value):$($progressMatch.Groups[2].Value)"
            }
        )
        if ($progressEvents.Count -eq 0) {
            $statusProgressPattern = "outbox_diag_progress=$([regex]::Escape($Stage))\|(repository-construction|versionsProbe|minimalClientBuilder|minimalSdkLogin|repositoryLogin|awaitConnected|createEncryptedConversation|awaitRoomReady|openConversation|persistDiagnosticRoom|verifyDiagnosticRoom|restoreSession|sendText|awaitLocalEcho|selectLocalEcho|assertOfflineEcho|awaitDelivery|verifyExactlyOnce)\|(start|complete)"
            $statusProgressMatches = [regex]::Matches($safeOutput, $statusProgressPattern)
            $progressEvents = @(
                foreach ($progressMatch in $statusProgressMatches) {
                    "$($progressMatch.Groups[1].Value):$($progressMatch.Groups[2].Value)"
                }
            )
        }
        $progressSummary = if ($progressEvents.Count -gt 0) {
            ($progressEvents | Select-Object -Unique) -join ","
        } else {
            "none"
        }
        $probeMatch = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_VERSIONS_PROBE result=(http-[1-5][0-9]{2}|transport-(UnknownHostException|ConnectException|NoRouteToHostException|SocketTimeoutException|SocketException|SSLException|SSLHandshakeException|ProtocolException|UnknownServiceException|InterruptedIOException|unclassified))"
        )
        if (-not $probeMatch.Success) {
            $probeMatch = [regex]::Match(
                $safeOutput,
                "outbox_diag_versions_probe=(http-[1-5][0-9]{2}|transport-(UnknownHostException|ConnectException|NoRouteToHostException|SocketTimeoutException|SocketException|SSLException|SSLHandshakeException|ProtocolException|UnknownServiceException|InterruptedIOException|unclassified))"
            )
        }
        $loginResponseMatch = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_LOGIN_RESPONSE httpStatus=(unknown|[1-5][0-9]{2}) errcode=(unknown|M_[A-Z0-9_]{1,64})"
        )
        if (-not $loginResponseMatch.Success) {
            $loginResponseMatch = [regex]::Match(
                $safeOutput,
                "outbox_diag_account_login=(unknown|[1-5][0-9]{2})\|(unknown|M_[A-Z0-9_]{1,64})"
            )
        }
        if ($probeMatch.Success) {
            Write-Output "OUTBOX_DIAG_PROBE result=$($probeMatch.Groups[1].Value)"
        }
        if ($loginResponseMatch.Success) {
            Write-Output "OUTBOX_DIAG_LOGIN_RESPONSE httpStatus=$($loginResponseMatch.Groups[1].Value) errcode=$($loginResponseMatch.Groups[2].Value)"
        }
        $accountLoginMatch = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_ACCOUNT_LOGIN httpStatus=(unknown|[1-5][0-9]{2}) errcode=(unknown|M_[A-Z0-9_]{1,64})"
        )
        if (-not $accountLoginMatch.Success) {
            $accountLoginMatch = [regex]::Match(
                $safeOutput,
                "outbox_diag_account_login=(unknown|[1-5][0-9]{2})\|(unknown|M_[A-Z0-9_]{1,64})"
            )
        }
        if ($accountLoginMatch.Success) {
            Write-Output "OUTBOX_DIAG_ACCOUNT_LOGIN httpStatus=$($accountLoginMatch.Groups[1].Value) errcode=$($accountLoginMatch.Groups[2].Value)"
        }
        $roomCreateProbeMatch = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_CONTROL_ROOM_CREATE httpStatus=(unknown|[1-5][0-9]{2}) errcode=(unknown|M_[A-Z0-9_]{1,64})"
        )
        if (-not $roomCreateProbeMatch.Success) {
            $roomCreateProbeMatch = [regex]::Match(
                $safeOutput,
                "outbox_diag_room_create_probe=(unknown|[1-5][0-9]{2})\|(unknown|M_[A-Z0-9_]{1,64})"
            )
        }
        if ($roomCreateProbeMatch.Success) {
            Write-Output "OUTBOX_DIAG_CONTROL_ROOM_CREATE httpStatus=$($roomCreateProbeMatch.Groups[1].Value) errcode=$($roomCreateProbeMatch.Groups[2].Value)"
        }
        $accountLogoutMatch = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_ACCOUNT_LOGOUT httpStatus=(unknown|[1-5][0-9]{2})"
        )
        if (-not $accountLogoutMatch.Success) {
            $accountLogoutMatch = [regex]::Match($safeOutput, "outbox_diag_account_logout=(unknown|[1-5][0-9]{2})")
        }
        if ($accountLogoutMatch.Success) {
            Write-Output "OUTBOX_DIAG_ACCOUNT_LOGOUT httpStatus=$($accountLogoutMatch.Groups[1].Value)"
        }
        $urlShapePattern = "OUTBOX_DIAG_URL_SHAPE boundary=(session|client) match=(true|false) scheme=(http|https|other|missing) host=(expected|loopback|other|missing) port=(expected|none|other|invalid) path=(root|other|missing) userinfo=(none|present) query=(none|present) fragment=(none|present)"
        foreach ($urlShape in [regex]::Matches($safeOutput, $urlShapePattern)) {
            Write-Output "OUTBOX_DIAG_URL_SHAPE boundary=$($urlShape.Groups[1].Value) match=$($urlShape.Groups[2].Value) scheme=$($urlShape.Groups[3].Value) host=$($urlShape.Groups[4].Value) port=$($urlShape.Groups[5].Value) path=$($urlShape.Groups[6].Value) userinfo=$($urlShape.Groups[7].Value) query=$($urlShape.Groups[8].Value) fragment=$($urlShape.Groups[9].Value)"
        }
        $urlShapeStatusPattern = "outbox_diag_url_shape=(session|client)\|(true|false)\|(http|https|other|missing)\|(expected|loopback|other|missing)\|(expected|none|other|invalid)\|(root|other|missing)\|(none|present)\|(none|present)\|(none|present)"
        foreach ($urlShape in [regex]::Matches($safeOutput, $urlShapeStatusPattern)) {
            Write-Output "OUTBOX_DIAG_URL_SHAPE boundary=$($urlShape.Groups[1].Value) match=$($urlShape.Groups[2].Value) scheme=$($urlShape.Groups[3].Value) host=$($urlShape.Groups[4].Value) port=$($urlShape.Groups[5].Value) path=$($urlShape.Groups[6].Value) userinfo=$($urlShape.Groups[7].Value) query=$($urlShape.Groups[8].Value) fragment=$($urlShape.Groups[9].Value)"
        }
        $sdkBoundaryPattern = "OUTBOX_DIAG_SDK_BOUNDARY boundary=(minimal-builder|minimal-sdk-login|minimal-sdk-session|minimal-sdk-session-read|minimal-session-user-match|minimal-session-url-match|minimal-client-url-match|minimal-sdk-user-id|minimal-sdk-profile-fetch|minimal-sdk-plain-room-create|minimal-sdk-e2ee-init|minimal-sdk-room-create|minimal-sdk-logout|repository-login|repository-close) result=(success|failure)(?: category=(network|auth|store|encryption|room|timeout|other) classChain=([A-Za-z0-9_.$>]+)(?: token=([A-Za-z0-9_-]+))?)?"
        foreach ($sdkBoundary in [regex]::Matches($safeOutput, $sdkBoundaryPattern)) {
            $boundaryName = $sdkBoundary.Groups[1].Value
            $boundaryResult = $sdkBoundary.Groups[2].Value
            if ($boundaryResult -eq "success") {
                Write-Output "OUTBOX_DIAG_SDK_BOUNDARY boundary=$boundaryName result=success"
            } else {
                Write-Output "OUTBOX_DIAG_SDK_BOUNDARY boundary=$boundaryName result=failure category=$($sdkBoundary.Groups[3].Value) classChain=$($sdkBoundary.Groups[4].Value) token=$($sdkBoundary.Groups[5].Value)"
            }
        }
        $sdkBoundaryStatusPattern = "outbox_diag_sdk_boundary=(minimal-builder|minimal-sdk-login|minimal-sdk-session|minimal-sdk-session-read|minimal-session-user-match|minimal-session-url-match|minimal-client-url-match|minimal-sdk-user-id|minimal-sdk-profile-fetch|minimal-sdk-plain-room-create|minimal-sdk-e2ee-init|minimal-sdk-room-create|minimal-sdk-logout|repository-login|repository-close)\|(success|failure)\|(none|network|auth|store|encryption|room|timeout|other)\|(none|[A-Za-z0-9_.$>]+)(?:\|([A-Za-z0-9_-]+))?"
        foreach ($sdkBoundary in [regex]::Matches($safeOutput, $sdkBoundaryStatusPattern)) {
            $boundaryName = $sdkBoundary.Groups[1].Value
            $boundaryResult = $sdkBoundary.Groups[2].Value
            if ($boundaryResult -eq "success") {
                Write-Output "OUTBOX_DIAG_SDK_BOUNDARY boundary=$boundaryName result=success"
            } else {
                Write-Output "OUTBOX_DIAG_SDK_BOUNDARY boundary=$boundaryName result=failure category=$($sdkBoundary.Groups[3].Value) classChain=$($sdkBoundary.Groups[4].Value) token=$($sdkBoundary.Groups[5].Value)"
            }
        }
        $reconnectStateStatusPattern = "outbox_diag_reconnect_state=(Connected|Reconnecting|Syncing|Offline|Other)\|([0-9]{1,2})\|(none|(?:Queued|Sending|Sent|Delivered|Failed|Other)(?:-(?:Queued|Sending|Sent|Delivered|Failed|Other))*)\|([0-9]{1,2})"
        $reconnectState = [regex]::Match($safeOutput, $reconnectStateStatusPattern)
        if ($reconnectState.Success) {
            Write-Output "OUTBOX_DIAG_RECONNECT_STATE connection=$($reconnectState.Groups[1].Value) matchingMessages=$($reconnectState.Groups[2].Value) states=$($reconnectState.Groups[3].Value) eventIds=$($reconnectState.Groups[4].Value)"
        }
        $testException = [regex]::Match(
            $safeOutput,
            "OUTBOX_DIAG_EXCEPTION stage=([A-Za-z0-9_-]+) step=([A-Za-z0-9_-]+) state=(start|complete) category=(network|auth|store|encryption|room|timeout|other) classChain=([A-Za-z0-9_.$>]+)"
        )
        if (-not $testException.Success) {
            $testException = [regex]::Match(
                $safeOutput,
                "outbox_diag_failure=([A-Za-z0-9_-]+)\|([A-Za-z0-9_-]+)\|(start|complete)\|(network|auth|store|encryption|room|timeout|other)\|([A-Za-z0-9_.$>]+)"
            )
        }
        if ($testException.Success) {
            $diagnosticStage = $testException.Groups[1].Value
            $diagnosticStep = $testException.Groups[2].Value
            $diagnosticState = $testException.Groups[3].Value
            $diagnosticCategory = $testException.Groups[4].Value
            $diagnosticClassChain = $testException.Groups[5].Value
            Write-Output "OUTBOX_DIAG_FAILURE stage=$diagnosticStage step=$diagnosticStep state=$diagnosticState category=$diagnosticCategory classChain=$diagnosticClassChain progress=$progressSummary boundary=$boundary exitCode=$exitCode completion=$instrumentationCompletion result=$resultPresence"
            throw "Isolated Android outbox instrumentation failed during stage '$Stage'; raw output, IDs, and credentials were suppressed."
        }
        Write-Output "OUTBOX_DIAG_FAILURE stage=$Stage step=unknown state=unknown category=other class=InstrumentationFailure progress=$progressSummary boundary=$boundary exitCode=$exitCode completion=$instrumentationCompletion result=$resultPresence"
        throw "Isolated Android outbox instrumentation failed during stage '$Stage'; raw output, IDs, and credentials were suppressed."
    }
    $roomCreateProbeMatch = [regex]::Match(
        $safeOutput,
        "OUTBOX_DIAG_CONTROL_ROOM_CREATE httpStatus=(unknown|[1-5][0-9]{2}) errcode=(unknown|M_[A-Z0-9_]{1,64})"
    )
    if (-not $roomCreateProbeMatch.Success) {
        $roomCreateProbeMatch = [regex]::Match(
            $safeOutput,
            "outbox_diag_room_create_probe=(unknown|[1-5][0-9]{2})\|(unknown|M_[A-Z0-9_]{1,64})"
        )
    }
    if ($roomCreateProbeMatch.Success) {
        Write-Output "OUTBOX_DIAG_CONTROL_ROOM_CREATE httpStatus=$($roomCreateProbeMatch.Groups[1].Value) errcode=$($roomCreateProbeMatch.Groups[2].Value)"
    }
    Write-Output $expectedResult.Value
}

function Invoke-AdbChecked {
    param([Parameter(Mandatory)] [string[]] $Arguments)
    $null = & $adb @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Android diagnostic command failed; command output was suppressed."
    }
}

$script:reverseMappingCreated = $false
$script:reverseMappingDevice = $null

function Get-AdbReverseMappings {
    param([Parameter(Mandatory)] [string] $Device)

    $rows = @(& $adb -s $Device reverse --list 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the selected emulator's reverse mappings."
    }
    foreach ($row in $rows) {
        $parts = ([string] $row).Trim() -split "\s+"
        if ($parts.Count -lt 2) { continue }

        # `adb -s <device> reverse --list` output varies across platform-tools:
        # some versions prefix the endpoint pair with a device serial, while
        # others return only the two endpoints. Since this query is already
        # scoped to $Device, parse the final two tokens and bind them to it.
        $localEndpoint = $parts[$parts.Count - 2]
        $remoteEndpoint = $parts[$parts.Count - 1]
        if ($localEndpoint -match '^tcp:\d+$' -and $remoteEndpoint -match '^tcp:\d+$') {
            [pscustomobject]@{ Device = $Device; Local = $localEndpoint; Remote = $remoteEndpoint }
        }
    }
}

function New-DiagnosticReverseMapping {
    param([Parameter(Mandatory)] [string] $Device)

    $localEndpoint = "tcp:$deviceHomeserverPort"
    $remoteEndpoint = "tcp:$hostHomeserverPort"
    $existing = @(Get-AdbReverseMappings -Device $Device)
    $portMappings = @($existing | Where-Object {
        $_.Device -eq $Device -and $_.Local -eq $localEndpoint
    })
    if ($portMappings.Count -gt 0) {
        throw "The dedicated diagnostic device port already has an adb reverse mapping."
    }

    $null = & $adb -s $Device reverse $localEndpoint $remoteEndpoint 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the isolated diagnostic adb reverse mapping."
    }
    $script:reverseMappingCreated = $true
    $script:reverseMappingDevice = $Device

    $created = @(Get-AdbReverseMappings -Device $Device | Where-Object {
        $_.Device -eq $Device -and $_.Local -eq $localEndpoint -and $_.Remote -eq $remoteEndpoint
    })
    if ($created.Count -ne 1) {
        throw "The isolated diagnostic adb reverse mapping could not be verified."
    }
    Write-Output "OUTBOX_DIAG reverse=ready devicePort=$deviceHomeserverPort hostLoopbackPort=$hostHomeserverPort"
}

function Remove-DiagnosticReverseMapping {
    if (-not $script:reverseMappingCreated -or [string]::IsNullOrWhiteSpace($script:reverseMappingDevice)) {
        return
    }

    $device = $script:reverseMappingDevice
    $localEndpoint = "tcp:$deviceHomeserverPort"
    $remoteEndpoint = "tcp:$hostHomeserverPort"
    try {
        $current = @(Get-AdbReverseMappings -Device $device)
        $exactMapping = @($current | Where-Object {
            $_.Device -eq $device -and $_.Local -eq $localEndpoint -and $_.Remote -eq $remoteEndpoint
        })
        if ($exactMapping.Count -ne 1) {
            Write-Output "OUTBOX_DIAG reverse=cleanup-skipped mapping=changed-or-missing"
            return
        }

        $null = & $adb -s $device reverse --remove $localEndpoint 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Output "OUTBOX_DIAG reverse=cleanup-failed"
            return
        }
        $remaining = @(Get-AdbReverseMappings -Device $device | Where-Object {
            $_.Device -eq $device -and $_.Local -eq $localEndpoint
        })
        if ($remaining.Count -eq 0) {
            Write-Output "OUTBOX_DIAG reverse=removed devicePort=$deviceHomeserverPort"
        } else {
            Write-Output "OUTBOX_DIAG reverse=cleanup-skipped mapping=changed-during-cleanup"
        }
    } catch {
        Write-Output "OUTBOX_DIAG reverse=cleanup-unverified"
    } finally {
        $script:reverseMappingCreated = $false
        $script:reverseMappingDevice = $null
    }
}

$script:synapseAccessBaseline = $null

function Get-SynapseAccessCounts {
    $arguments = @(
        "compose", "--project-name", $composeProject,
        "--file", $composeFile, "--env-file", $composeEnvFile,
        "logs", "--no-color", "--no-log-prefix", "synapse"
    )
    $rawLogLines = @(& $docker @arguments 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    $counts = @{}
    foreach ($line in $rawLogLines) {
        $request = [regex]::Match(
            [string] $line,
            '"(?<method>GET|POST)\s+(?<path>/[^"\s?]+)(?:\?[^"\s]*)?\s+HTTP/[0-9.]+"'
        )
        if (-not $request.Success) { continue }

        $prefix = ([string] $line).Substring(0, $request.Index)
        $suffix = ([string] $line).Substring($request.Index + $request.Length)
        $status = [regex]::Match($prefix, '(?<status>[1-5][0-9]{2})\s*$')
        if (-not $status.Success) {
            $status = [regex]::Match($suffix, '^\s*(?<status>[1-5][0-9]{2})(?=\s|$)')
        }
        if (-not $status.Success) { continue }

        $path = $request.Groups["path"].Value
        $endpoint = if ($path -eq "/_matrix/client/versions") {
            "versions"
        } elseif ($path -match '^/_matrix/client/(?:v3|r0|unstable)/login$' -or $path -eq "/_matrix/client/login") {
            "login"
        } elseif ($path -match '^/_matrix/client/(?:v3|r0|unstable)/logout$' -or $path -eq "/_matrix/client/logout") {
            "logout"
        } elseif ($path -match '^/_matrix/client/(?:v3|r0|unstable)/profile(?:/.*)?$') {
            "profile"
        } elseif ($path -match '^/_matrix/client/(?:v3|r0|unstable)/createRoom$') {
            "room-create"
        } else {
            continue
        }

        $statusCode = $status.Groups["status"].Value
        $key = "$endpoint|$statusCode"
        if (-not $counts.ContainsKey($key)) { $counts[$key] = 0 }
        $counts[$key]++
    }
    return ,$counts
}

function Write-SynapseAccessDelta {
    $current = Get-SynapseAccessCounts
    if ($null -eq $current) {
        Write-Output "OUTBOX_DIAG_SERVER_ACCESS endpoint=versions status=unknown"
        return
    }

    foreach ($key in @($current.Keys | Sort-Object)) {
        $fields = $key -split "\|"
        if ($fields.Count -ne 2 -or $fields[0] -notin @("versions", "login", "logout", "profile", "room-create") -or $fields[1] -notmatch '^[1-5][0-9]{2}$') {
            continue
        }
        $baselineCount = 0
        if ($null -ne $script:synapseAccessBaseline -and $script:synapseAccessBaseline.ContainsKey($key)) {
            $baselineCount = [int] $script:synapseAccessBaseline[$key]
        }
        $delta = [int] $current[$key] - $baselineCount
        if ($delta -gt 0) {
            Write-Output "OUTBOX_DIAG_SERVER_ACCESS endpoint=$($fields[0]) status=$($fields[1]) count=$delta"
        }
    }
}

$composeTouched = $false
$serverStoppedForOfflineStage = $false
try {
    Write-Output "OUTBOX_DIAG build=starting variant=outboxDiagDebug"
    $buildArguments = @(
        "run", "--rm",
        "--volume", "${workspace}:/workspace",
        "--workdir", "/workspace",
        "--env", "ANDROID_HOME=/workspace/.tools/android-sdk-linux",
        "--env", "ANDROID_SDK_ROOT=/workspace/.tools/android-sdk-linux",
        "--env", "GRADLE_USER_HOME=/workspace/.tools/gradle-cache-linux",
        "--env", "JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=512m",
        "--env", "PRIVATE_MESSENGER_DEBUG_KEYSTORE=/workspace/.tools/private-messenger-debug.keystore",
        "--env", "PRIVATE_MESSENGER_DEBUG_KEYSTORE_PASSWORD=android",
        "gradle:9.5.0-jdk17", "gradle",
        ":app:assembleOutboxDiagDebug", ":app:assembleOutboxDiagDebugAndroidTest",
        "--no-daemon", "--console=plain"
    )
    if ($ForceBuild) { $buildArguments += @("--rerun-tasks", "--max-workers=2") }
    & $docker @buildArguments
    if ($LASTEXITCODE -ne 0) {
        throw "The isolated outbox diagnostic APK build failed."
    }

    $diagnosticApks = Get-ChildItem -LiteralPath $apkOutputDirectory -Filter "*.apk" -File -Recurse |
        Where-Object { $_.Name -match "outbox.?diag.*debug" }
    $appApk = $diagnosticApks |
        Where-Object { $_.FullName -notmatch "androidTest" } | Select-Object -First 1
    $testApk = $diagnosticApks |
        Where-Object { $_.FullName -match "androidTest" } | Select-Object -First 1
    if ($null -eq $appApk -or $null -eq $testApk) {
        throw "The isolated diagnostic APK outputs were not found."
    }

    $deviceOutput = & $adb devices 2>$null
    $devices = @($deviceOutput | Where-Object { $_ -match "^\S+\s+device$" })
    if ($devices.Count -ne 1) {
        throw "Outbox validation requires exactly one attached Android emulator."
    }
    $device = ($devices[0] -split "\s+")[0]
    $isEmulator = (& $adb -s $device shell getprop ro.kernel.qemu 2>$null | Out-String).Trim()
    if ($isEmulator -ne "1") {
        throw "Outbox validation requires an Android emulator for the isolated adb reverse route."
    }
    New-DiagnosticReverseMapping -Device $device

    Invoke-AdbChecked -Arguments @("-s", $device, "install", "-r", $appApk.FullName)
    Invoke-AdbChecked -Arguments @("-s", $device, "install", "-r", $testApk.FullName)
    Invoke-AdbChecked -Arguments @("-s", $device, "shell", "pm", "clear", $applicationId)
    Invoke-AdbChecked -Arguments @("-s", $device, "shell", "pm", "clear", $testApplicationId)

    $composeTouched = $true
    Initialize-DiagnosticSynapse
    Write-Output "OUTBOX_DIAG server=ready bind=127.0.0.1 port=8009 accounts=fresh"

    $runId = [Guid]::NewGuid().ToString("N")
    $sender = "diag$runId"
    $peer = "peer$runId"
    $senderPassword = New-RandomPassword
    $peerPassword = New-RandomPassword
    $marker = "outbox-$runId"
    $senderUserId = "@$sender`:$homeserverName"
    $recipientUserId = "@$peer`:$homeserverName"
    Invoke-ProvisionFreshAccounts `
        -SenderLocalpart $sender `
        -SenderPassword $senderPassword `
        -PeerLocalpart $peer `
        -PeerPassword $peerPassword
    Write-Output "OUTBOX_DIAG accounts=provisioned count=2 output=redacted"
    $script:synapseAccessBaseline = Get-SynapseAccessCounts

    Invoke-DiagnosticInstrumentation -Device $device -Stage "prepare" -Marker $marker `
        -Username $senderUserId -Password $senderPassword -RecipientUserId $recipientUserId `
        -LoginOnly:$LoginOnly
    Invoke-AdbChecked -Arguments @("-s", $device, "shell", "am", "force-stop", $applicationId)

    if ($LoginOnly) {
        Write-Output "OUTBOX_DIAG result=login-only completed=prepare"
    } else {
        Invoke-DockerCompose -Arguments @("stop", "synapse")
        $serverStoppedForOfflineStage = $true
        if (Test-DiagnosticSynapse) {
            throw "The isolated Synapse listener remained reachable during the offline phase."
        }
        Write-Output "OUTBOX_DIAG server=stopped phase=offline"

        Invoke-DiagnosticInstrumentation -Device $device -Stage "seed-offline" -Marker $marker
        Invoke-AdbChecked -Arguments @("-s", $device, "shell", "am", "force-stop", $applicationId)

        Invoke-DockerCompose -Arguments @("up", "-d", "synapse")
        $serverStoppedForOfflineStage = $false
        Wait-DiagnosticSynapse
        Write-Output "OUTBOX_DIAG server=ready phase=resume"

        Invoke-DiagnosticInstrumentation -Device $device -Stage "resume" -Marker $marker
        Write-Output "OUTBOX_DIAG result=passed accountData=isolated"
    }
} finally {
    if ($composeTouched) {
        Write-SynapseAccessDelta
    }
    if ($serverStoppedForOfflineStage -and $composeTouched) {
        $null = Invoke-DockerCompose -Arguments @("up", "-d", "synapse") -AllowFailure
    }
    Remove-DiagnosticReverseMapping
    if ($composeTouched) {
        $null = Invoke-DockerCompose -Arguments @("down") -AllowFailure
        Write-Output "OUTBOX_DIAG server=stopped volumes=retained"
    }
}
