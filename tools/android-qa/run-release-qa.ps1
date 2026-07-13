[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$VideoAPath,
    [Parameter(Mandatory = $true)][string]$VideoBPath,
    [string]$Serial = "emulator-5554",
    [int]$MaxWaitMinutes = 150,
    [string]$ScanProfile = "1-minute checkpoints",
    [string]$ArtifactDirectory = "qa-artifacts"
)

$ErrorActionPreference = "Stop"
$package = "com.hughbechainez.compilationmaker"
$activity = "$package/com.example.compilationmaker.MainActivity"
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
New-Item -ItemType Directory -Force -Path $ArtifactDirectory | Out-Null

function Adb([string[]]$AdbArgs) {
    & $adb -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($AdbArgs -join ' ')" }
}

function Dump-Ui {
    $remote = "/sdcard/qa-window.xml"
    Adb @("shell", "uiautomator", "dump", $remote) | Out-Null
    $xml = (& $adb -s $Serial exec-out cat $remote | Out-String)
    [xml]$xml
}

function Find-UiNode([xml]$Ui, [string]$ResourceId, [string]$TextContains) {
    $nodes = $Ui.SelectNodes("//node")
    foreach ($node in $nodes) {
        if ($ResourceId -and $node.'resource-id' -eq $ResourceId) { return ,(Convert-UiElement $node) }
        if ($TextContains -and (($node.text -like "*$TextContains*") -or ($node.'content-desc' -like "*$TextContains*"))) { return ,(Convert-UiElement $node) }
    }
    return $null
}

function Convert-UiElement($Element) {
    [pscustomobject]@{
        Bounds = $Element.GetAttribute("bounds")
        Text = $Element.GetAttribute("text")
        ContentDescription = $Element.GetAttribute("content-desc")
    }
}

function Tap-UiNode($Node, [string]$Label = "UI node") {
    if ($null -eq $Node) { throw "$Label not found" }
    $bounds = if ($Node -is [System.Xml.XmlElement]) { $Node.GetAttribute("bounds") } else { [string]$Node.bounds }
    if ([string]::IsNullOrWhiteSpace($bounds) -and $Node -is [System.Xml.XmlAttribute] -and $Node.OwnerElement) {
        $bounds = $Node.OwnerElement.GetAttribute("bounds")
    }
    if ([string]::IsNullOrWhiteSpace($bounds)) {
        $rawNode = [string]$Node
        if ($rawNode -match 'bounds="([^"]+)"') { $bounds = $Matches[1] }
        if ([string]::IsNullOrWhiteSpace($bounds) -and $rawNode -match 'Bounds=([^;]+)') { $bounds = $Matches[1].Trim() }
    }
    if ($bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "Invalid bounds for ${Label}: $bounds type=$($Node.GetType().FullName) value=$([string]$Node)" }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Adb @("shell", "input", "tap", $x, $y)
}

function Wait-ForDevice {
    for ($i = 0; $i -lt 90; $i++) {
        $state = (& $adb devices | Select-String "^$Serial\s+device$")
        if ($state) {
            $boot = (& $adb -s $Serial shell getprop sys.boot_completed).Trim()
            if ($boot -eq "1") { return }
        }
        Start-Sleep -Seconds 2
    }
    throw "Emulator did not become ready: $Serial"
}

if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
if (-not (Test-Path $VideoAPath)) { throw "Video A not found: $VideoAPath" }
if (-not (Test-Path $VideoBPath)) { throw "Video B not found: $VideoBPath" }

if (-not (& $adb devices | Select-String "^$Serial\s+device$")) {
    Start-Process -WindowStyle Hidden -FilePath $emulator -ArgumentList "@$($env:COMPILATIONMAKER_AVD ?? 'CompilationMaker_API35')", "-no-snapshot", "-no-boot-anim", "-no-audio", "-gpu", "auto", "-memory", "4096"
}
Wait-ForDevice
Adb @("install", "-r", $ApkPath)
Adb @("push", $VideoAPath, "/sdcard/Download/compilation_test_video_A.mp4")
Adb @("push", $VideoBPath, "/sdcard/Download/compilation_test_video_B.mp4")
Adb @("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/compilation_test_video_A.mp4") | Out-Null
Adb @("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/compilation_test_video_B.mp4") | Out-Null
Start-Sleep -Seconds 3

function Allow-Permissions {
    for ($i = 0; $i -lt 6; $i++) {
        Start-Sleep -Milliseconds 500
        $ui = Dump-Ui
        $allow = Find-UiNode $ui "com.android.permissioncontroller:id/permission_allow_button" $null
        if (-not $allow) { $allow = Find-UiNode $ui "com.android.permissioncontroller:id/permission_allow_all_button" $null }
        if ($allow) { Tap-UiNode $allow } else { return }
    }
}

function Dismiss-SystemOverlays {
    for ($i = 0; $i -lt 3; $i++) {
        Start-Sleep -Milliseconds 300
        $ui = Dump-Ui
        $gotIt = Find-UiNode $ui "android:id/ok" "Got it"
        if ($gotIt) { Tap-UiNode $gotIt } else { return }
    }
}

function Select-Video([string]$Title) {
    $select = $null
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Milliseconds 500
        $ui = Dump-Ui
        $select = Find-UiNode $ui "com.hughbechainez.compilationmaker:id/selectButton" $null
        if ($select) { break }
    }
    Tap-UiNode $select "select video button"
    Dismiss-SystemOverlays
    # Prefer the local Downloads root. Recent may be backed by Google Photos,
    # whose click action opens a preview instead of returning a document URI.
    $roots = Find-UiNode (Dump-Ui) $null "Show roots"
    if ($roots) {
        Tap-UiNode $roots "picker roots"
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Milliseconds 500
            $ui = Dump-Ui
            $downloads = Find-UiNode $ui $null "Downloads"
            if ($downloads) { Tap-UiNode $downloads "Downloads root"; break }
        }
    }
    for ($i = 0; $i -lt 8; $i++) {
        Start-Sleep -Seconds 1
        Dismiss-SystemOverlays
        $ui = Dump-Ui
        $title = Find-UiNode $ui $null $Title
        if ($title) {
            $card = $ui.SelectSingleNode("//node[@resource-id='com.google.android.documentsui:id/item_root'][.//node[contains(@text,'$Title')]][1]")
            $tapNode = if ($card) { Convert-UiElement $card } else { $title }
            Tap-UiNode $tapNode "video card $Title"
            Allow-Permissions
            return
        }
    }

    $roots = Find-UiNode (Dump-Ui) $null "Show roots"
    if ($roots) {
        Tap-UiNode $roots "picker roots"
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Milliseconds 500
            $ui = Dump-Ui
            $downloads = Find-UiNode $ui $null "Downloads"
            if ($downloads) { Tap-UiNode $downloads "Downloads root"; break }
        }
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Seconds 1
            $ui = Dump-Ui
            $title = Find-UiNode $ui $null $Title
            if ($title) {
                $card = $ui.SelectSingleNode("//node[@resource-id='com.google.android.documentsui:id/item_root'][.//node[contains(@text,'$Title')]][1]")
                $tapNode = if ($card) { Convert-UiElement $card } else { $title }
            Tap-UiNode $tapNode "video card $Title"
                Allow-Permissions
                return
            }
        }
    }

    $ui = Dump-Ui
    $search = Find-UiNode $ui "com.google.android.documentsui:id/option_menu_search" $null
    if ($search) {
        Tap-UiNode $search "picker search"
        Start-Sleep -Milliseconds 500
        Adb @("shell", "input", "text", $Title)
        for ($i = 0; $i -lt 20; $i++) {
            Start-Sleep -Seconds 1
            $ui = Dump-Ui
            $title = Find-UiNode $ui $null $Title
            if ($title) {
                $card = $ui.SelectSingleNode("//node[@resource-id='com.google.android.documentsui:id/item_root'][.//node[contains(@text,'$Title')]][1]")
                $tapNode = if ($card) { Convert-UiElement $card } else { $title }
                Tap-UiNode $tapNode "video search result $Title"
                Allow-Permissions
                return
            }
        }
    }
    throw "Video was not visible in Android picker: $Title"
}

function Start-Compilation {
    $ui = Dump-Ui
    $process = Find-UiNode $ui "com.hughbechainez.compilationmaker:id/processButton" $null
    if (-not $process) {
        Adb @("shell", "input", "swipe", "540", "2200", "540", "600", "600")
        Start-Sleep -Seconds 1
        $ui = Dump-Ui
        $process = Find-UiNode $ui "com.hughbechainez.compilationmaker:id/processButton" $null
    }
    Tap-UiNode $process
}

function Set-ScanProfile {
    $ui = Dump-Ui
    $picker = Find-UiNode $ui "com.hughbechainez.compilationmaker:id/scanSpeedPicker" $null
    if (-not $picker) {
        Adb @("shell", "input", "swipe", "540", "1900", "540", "700", "600")
        Start-Sleep -Milliseconds 500
        $ui = Dump-Ui
        $picker = Find-UiNode $ui "com.hughbechainez.compilationmaker:id/scanSpeedPicker" $null
    }
    Tap-UiNode $picker "scan profile picker"
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Milliseconds 300
        $ui = Dump-Ui
        $option = Find-UiNode $ui $null $ScanProfile
        if ($option) { Tap-UiNode $option "scan profile option $ScanProfile"; return }
    }
    throw "Scan profile option not found: $ScanProfile"
}

function Run-One([string]$Title) {
    Adb @("shell", "am", "force-stop", $package)
    Adb @("shell", "am", "force-stop", "com.google.android.documentsui")
    Adb @("shell", "am", "force-stop", "com.google.android.apps.photos")
    Adb @("shell", "am", "start", "-n", $activity) | Out-Null
    Start-Sleep -Seconds 2
    Dismiss-SystemOverlays
    Allow-Permissions
    Select-Video $Title
    Set-ScanProfile
    Start-Compilation
    $deadline = (Get-Date).AddMinutes($MaxWaitMinutes)
    while ((Get-Date) -lt $deadline) {
        $log = (& $adb -s $Serial logcat -d -t 300) -join "`n"
        $log | Out-File (Join-Path $ArtifactDirectory "$($Title -replace '\W','_').log") -Encoding utf8
        if ($log -match '\[worker\] returning success' -and $log -match '\[verify\] output exists') { return $true }
        if ($log -match 'Compilation failed|returning failure|returning no_results') { return $false }
        Start-Sleep -Seconds 10
    }
    throw "Timed out waiting for compilation: $Title"
}

$a = Run-One "compilation_test_video_A.mp4"
$b = Run-One "compilation_test_video_B.mp4"
if (-not ($a -and $b)) { throw "Video A/B self-test failed" }
Write-Output "Video A/B self-test passed on $Serial"
