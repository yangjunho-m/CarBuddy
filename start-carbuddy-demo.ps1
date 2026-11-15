$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'CarBuddy\Pleos'
$sdk = Join-Path $root 'sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$image = 'system-images/android-34/pleos_car_emulator/x86_64/'
$avd = 'CarBuddy-Pleos'
$google = 'https://dl.google.com/android/repository/repository2-3.xml'
$pleos = 'https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml'

function Find-Pleos([string]$adbPath) {
    foreach ($line in (& $adbPath devices)) {
        if ($line -match '^(emulator-\d+)\s+device\s*$') {
            $serial = $Matches[1]
            if ((& $adbPath -s $serial shell getprop ro.product.device 2>$null) -like 'crp_avd*') {
                $serial
            }
        }
    }
}

function Read-Catalog([string]$url) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 30
    $response.RawContentStream.Position = 0
    $catalog = [xml]::new()
    $catalog.Load($response.RawContentStream)
    $catalog
}

function Install-SdkPackage($catalog, [uri]$source, $packageId, $destination, $requiredFile) {
    if (Test-Path -LiteralPath (Join-Path $destination $requiredFile)) { return }

    $package = $catalog.SelectSingleNode("/*/remotePackage[@path='$packageId'][channelRef/@ref='channel-0']")
    $archive = $package.SelectSingleNode("archives/archive[not(host-os) or host-os='windows'][not(host-arch) or host-arch='x64']/complete")
    $licenseId = $package.'uses-license'.ref
    $license = $catalog.SelectSingleNode("/*/license[@id='$licenseId']").InnerText
    $licenseFile = Join-Path $root "$licenseId.txt"
    if (!(Test-Path -LiteralPath $licenseFile) -or (Get-Content -Raw -LiteralPath $licenseFile) -cne $license) {
        Write-Host $license
        if ((Read-Host "Accept $licenseId to install ${packageId}? [y/N]") -ne 'y') {
            throw 'Installation stopped. Run again if you decide to accept the terms.'
        }
        Set-Content -LiteralPath $licenseFile -Value $license -Encoding UTF8 -NoNewline
    }

    $url = [uri]::new($source, $archive.url)
    if ($url.Scheme -ne 'https' -or $url.Host -ne $source.Host) { throw "Unexpected download URL $url" }
    $size = [long]$archive.size
    $sha1 = $archive.SelectSingleNode('checksum').InnerText
    if ($size -le 0 -or $sha1 -notmatch '^[a-fA-F0-9]{40}$') { throw "Invalid package metadata for $packageId" }
    $zip = Join-Path $root "$sha1.zip"
    $local = Get-Item -LiteralPath $zip -ErrorAction SilentlyContinue
    if ($local -and $local.Length -gt $size) {
        Remove-Item -LiteralPath $zip
        $local = $null
    }
    if (!$local -or $local.Length -ne $size) {
        Write-Host "Downloading $packageId ($([math]::Round($size / 1GB, 2)) GiB)..."
        & curl.exe --fail --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 20 --continue-at - --output $zip $url.AbsoluteUri
        if ($LASTEXITCODE -ne 0) { throw "Download interrupted. Run again to retry $packageId." }
    }
    if ((Get-Item -LiteralPath $zip).Length -ne $size -or (Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash -ne $sha1) {
        Remove-Item -LiteralPath $zip
        throw "Checksum mismatch for $packageId. Run again to download a new copy."
    }

    Write-Host "Installing $packageId..."
    $stage = Join-Path $root ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    try {
        & tar.exe -xf $zip -C $stage
        if ($LASTEXITCODE -ne 0) { throw "Unable to unpack $packageId. Check available disk space and retry." }
        $payload = @(Get-ChildItem -LiteralPath $stage -Directory)
        if ($payload.Count -ne 1 -or !(Test-Path -LiteralPath (Join-Path $payload[0].FullName $requiredFile))) {
            throw "Unexpected package layout for $packageId."
        }
        if (Test-Path -LiteralPath $destination) { throw "Incomplete SDK folder at $destination. Move it aside and retry." }
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Move-Item -LiteralPath $payload[0].FullName -Destination $destination
        Remove-Item -LiteralPath $zip
    } finally {
        # This staging path was created in the private SDK directory above.
        $resolved = (Resolve-Path -LiteralPath $stage).ProviderPath
        $installRoot = (Resolve-Path -LiteralPath $root).ProviderPath.TrimEnd('\') + '\'
        if ($resolved.StartsWith($installRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

function Start-Pleos {
    if (Get-Process emulator -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $emulator }) { return }
    if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'Automatic setup requires 64-bit Windows on an Intel or AMD PC.' }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_AVD_HOME = Join-Path $root 'avd'
    $env:ANDROID_USER_HOME = Join-Path $root 'android'
    $imageDir = Join-Path $sdk $image.TrimEnd('/')

    if (!(Test-Path -LiteralPath $adb) -or !(Test-Path -LiteralPath $emulator)) {
        $catalog = Read-Catalog $google
        Install-SdkPackage $catalog $google 'platform-tools' (Split-Path -Parent $adb) 'adb.exe'
        Install-SdkPackage $catalog $google 'emulator' (Split-Path -Parent $emulator) 'emulator.exe'
    }
    $accel = & $emulator -accel-check
    if ($LASTEXITCODE -ne 0 -or $accel -notcontains '0') {
        $accel | Write-Host
        throw 'Enable CPU virtualization in BIOS and Windows Hypervisor Platform, restart Windows, and run this file again. https://developer.android.com/studio/run/emulator-acceleration'
    }

    if (!(Test-Path -LiteralPath (Join-Path $imageDir 'devices.xml'))) {
        $catalog = Read-Catalog $pleos
        Install-SdkPackage $catalog $pleos 'system-images;android-34;pleos_car_emulator;x86_64' $imageDir 'devices.xml'
    }

    $avdDir = Join-Path $env:ANDROID_AVD_HOME "$avd.avd"
    New-Item -ItemType Directory -Path $avdDir, $env:ANDROID_USER_HOME -Force | Out-Null
    $config = Join-Path $avdDir 'config.ini'
    if (!(Test-Path -LiteralPath $config)) {
        [xml]$profile = Get-Content -LiteralPath (Join-Path $imageDir 'devices.xml') -Raw
        $screen = $profile.devices.device.hardware.screen
        @"
AvdId=$avd
avd.ini.displayname=CarBuddy Pleos
target=android-34
image.sysdir.1=$image
tag.id=android-automotive
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.lcd.width=$($screen.dimensions.'x-dimension')
hw.lcd.height=$($screen.dimensions.'y-dimension')
hw.lcd.density=$($screen.xdpi)
hw.initialOrientation=landscape
hw.keyboard=yes
hw.mainKeys=no
disk.dataPartition.size=10G
showDeviceFrame=no
"@ | Set-Content -LiteralPath $config -Encoding ASCII
    }
    @"
avd.ini.encoding=UTF-8
path=$avdDir
target=android-34
"@ | Set-Content -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avd.ini") -Encoding UTF8

    Write-Host 'Starting Pleos Connect. Initial boot can take a few minutes...'
    & $adb start-server
    Start-Process -FilePath $emulator -ArgumentList "-avd $avd -no-snapshot" `
        -RedirectStandardOutput (Join-Path $root 'emulator.log') `
        -RedirectStandardError (Join-Path $root 'emulator-error.log')
}

try {
    $apk = Join-Path $PSScriptRoot 'apk\CarBuddy-debug.apk'
    if (!(Test-Path -LiteralPath $apk)) { throw 'APK missing. Extract the full CarBuddy ZIP before running this file.' }

    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $lock = [IO.File]::Open((Join-Path $root 'launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), $sdk)
    $sdkRoots += @(Get-Process emulator -ErrorAction SilentlyContinue | ForEach-Object { Split-Path -Parent (Split-Path -Parent $_.Path) })
    $adbPaths = @(foreach ($dir in $sdkRoots) { if ($dir) { Join-Path $dir 'platform-tools\adb.exe' } })
    $adbPaths += @(Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
    $currentAdb = $adbPaths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $devices = @()
    if ($currentAdb) { $devices = @(Find-Pleos $currentAdb) }
    if (!$devices.Count) {
        Start-Pleos
        $currentAdb = $adb
    }

    $deadline = (Get-Date).AddMinutes(10)
    do {
        $devices = @(Find-Pleos $currentAdb)
        if ($devices.Count -gt 1) { throw 'More than one Pleos emulator is online. Leave one running and retry.' }
        $serial = $devices | Select-Object -First 1
        $ready = $serial -and (& $currentAdb -s $serial shell getprop sys.boot_completed 2>$null) -eq '1'
        if ($ready) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if (!$ready) { throw "Pleos did not finish booting. See $root\emulator.log." }

    Write-Host "Installing CarBuddy on $serial..."
    & $currentAdb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not install CarBuddy.' }
    $launch = & $currentAdb -s $serial shell am start --user current -n app.carbuddy/.MainActivity
    if ($LASTEXITCODE -ne 0 -or ($launch -match '^(Error|Exception)')) { throw ($launch -join [Environment]::NewLine) }

    $forward = (& $currentAdb forward --list) | Where-Object { $_ -match "^$serial tcp:\d+ tcp:8088$" } | Select-Object -First 1
    if ($forward -match 'tcp:(\d+) tcp:8088$') {
        $port = $Matches[1]
    } else {
        $port = 8088
        & $currentAdb -s $serial forward --no-rebind tcp:8088 tcp:8088 2>$null
        if ($LASTEXITCODE -ne 0) {
            $port = & $currentAdb -s $serial forward tcp:0 tcp:8088
            if ($LASTEXITCODE -ne 0 -or $port -notmatch '^\d+$') { throw 'Could not forward the Virtual Console port.' }
        }
    }
    $url = "http://127.0.0.1:$port/"
    $deadline = (Get-Date).AddSeconds(45)
    do {
        & curl.exe --noproxy '*' -fsS --max-time 2 -o NUL $url 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Opening Pleos Virtual Console at $url"
            Start-Process $url
            exit 0
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Virtual Console did not respond at $url. Restart Pleos and retry."
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
}
