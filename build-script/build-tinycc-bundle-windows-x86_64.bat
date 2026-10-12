@echo off
rem Builds the windows-x86_64 tinycc bundle ON a Windows host (the CI entry point; on Linux the
rem cross-compiling build-script/build-tinycc-bundle-windows-x86_64.sh produces the same layout).
rem The FFM backend loads bin/libtcc.dll in-process, so unlike the CLI work tree this bundle must
rem carry the shared library plus the full runtime surface.
rem
rem Output: build/tinycc-bundle/windows-x86_64/
rem   bin/libtcc.dll         FFM loading target
rem   include/               merged tinycc + win32 headers (winapi under include/winapi/; the flat
rem     (winapi inside)      merge is load-bearing: windows.h depends on _mingw.h next to it)
rem   lib/libtcc1.a          runtime archive (PE library search expands {B}/lib)
rem   lib/*.def              import definitions (msvcrt, kernel32, user32, gdi32, ws2_32)
rem   VERSION, MANIFEST, COPYING, RELICENSING — same contract as the Linux bundle
rem
rem Strategy: reuse build-tinycc-cli-windows-x86_64.bat for the pristine work tree (config.h,
rem merged headers, bootstrapped lib/libtcc1.a — the CLI's u8 probe already proves the vendored
rem patch is live in this tree), then build libtcc.dll with zig cc and assemble the bundle.
setlocal
set SCRIPT_DIR=%~dp0
set REPO_ROOT=%SCRIPT_DIR%..
set SRC_DIR=%REPO_ROOT%\src\main\c\tinycc
set CLI_WORK_DIR=%REPO_ROOT%\build\tinycc\windows-x86_64
set BUNDLE_DIR=%REPO_ROOT%\build\tinycc-bundle\windows-x86_64
set TINYCC_COMMIT=43c7708b85681a2fd4451c8a541af4494a8919b2
set BUNDLE_FORMAT_VERSION=1
set PLATFORM_KEY=windows-x86_64
set ZIG_TARGET=x86_64-windows-gnu

rem ---- preconditions ---------------------------------------------------------
where zig >nul 2>nul || (
    echo error: zig is required ^(used as the bootstrap C compiler via 'zig cc'^) >&2
    exit /B 1
)
findstr /C:"%TINYCC_COMMIT%" "%SRC_DIR%\GDCC_PIN.md" >nul || (
    echo error: %SRC_DIR%\GDCC_PIN.md does not record the pinned commit %TINYCC_COMMIT% — vendored tree and script disagree >&2
    exit /B 1
)
where powershell >nul 2>nul || (
    echo error: powershell is required for MANIFEST generation >&2
    exit /B 1
)

rem ---- CLI work tree (shared with the Gradle buildTinyccCli task) ---------------
rem Only rebuilt when absent: the CLI script wipes and regenerates the tree, and its cli-verified
rem marker proves the tree passed the u8 probe. Gradle's up-to-date check covers staleness.
if not exist "%CLI_WORK_DIR%\cli-verified" (
    call "%SCRIPT_DIR%build-tinycc-cli-windows-x86_64.bat" || goto :fail
)

rem ---- libtcc.dll ---------------------------------------------------------------
rem Same single-file build as upstream: libtcc.c includes everything under ONE_SOURCE=1.
rem -fno-sanitize=undefined: zig's default UBSan trips an alignment panic in the PE import
rem generation path (pe_build_imports).
cd /d "%CLI_WORK_DIR%"
zig cc -target %ZIG_TARGET% -fno-sanitize=undefined -shared -DTCC_TARGET_PE -DTCC_TARGET_X86_64 -DLIBTCC_AS_DLL libtcc.c -o libtcc.dll || goto :fail

rem ---- assemble the bundle -------------------------------------------------------
rem Never publish over a previous bundle: wipe first; a failed removal must not be built on top of.
if exist "%BUNDLE_DIR%" rmdir /S /Q "%BUNDLE_DIR%"
if exist "%BUNDLE_DIR%" (
    echo error: failed to remove %BUNDLE_DIR% >&2
    exit /B 1
)
mkdir "%BUNDLE_DIR%\bin"
mkdir "%BUNDLE_DIR%\lib"
copy /y "%CLI_WORK_DIR%\libtcc.dll" "%BUNDLE_DIR%\bin\libtcc.dll" >nul || goto :fail
xcopy /s /e /q /y "%CLI_WORK_DIR%\include\." "%BUNDLE_DIR%\include\" >nul || goto :fail
rem tcclib.h lives at the source root (not under include/) but is part of the runtime header set.
copy /y "%CLI_WORK_DIR%\tcclib.h" "%BUNDLE_DIR%\include\" >nul || goto :fail
xcopy /s /e /q /y "%CLI_WORK_DIR%\lib\." "%BUNDLE_DIR%\lib\" >nul || goto :fail
copy /y "%SRC_DIR%\COPYING" "%BUNDLE_DIR%\COPYING" >nul || goto :fail
copy /y "%SRC_DIR%\RELICENSING" "%BUNDLE_DIR%\RELICENSING" >nul || goto :fail

(
    echo bundleFormat=%BUNDLE_FORMAT_VERSION%
    echo tinyccCommit=%TINYCC_COMMIT%
    echo platform=%PLATFORM_KEY%
) > "%BUNDLE_DIR%\VERSION"

rem MANIFEST: sha256sum-compatible lines (`<lowercase-hash><two spaces><relative/path>`,
rem ordinal-sorted, forward slashes), covering every file except MANIFEST itself. The write target
rem is absolute: Windows PowerShell 5.1's Set-Location does not re-sync the process working
rem directory that [System.IO.File] resolves relative paths against.
powershell -NoProfile -Command "Set-Location -LiteralPath '%BUNDLE_DIR%'; $root = (Get-Location).ProviderPath.TrimEnd('\'); $files = @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $_.Name -ne 'MANIFEST' }); $lines = @(foreach ($f in $files) { $rel = $f.FullName.Substring($root.Length + 1) -replace '\\', '/'; $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash.ToLowerInvariant(); \"$h  $rel\" }); [Array]::Sort($lines, [System.StringComparer]::Ordinal); [System.IO.File]::WriteAllLines((Join-Path $root 'MANIFEST'), $lines, (New-Object System.Text.UTF8Encoding($false)))" || goto :fail

rem ---- verify: structural ----------------------------------------------------------
rem PE artifacts are exercised by the test suite on this host; here we pin the magic and every
rem layout entry the runtime contract requires.
powershell -NoProfile -Command "if ((Get-Content -Encoding Byte -TotalCount 2 '%BUNDLE_DIR%\bin\libtcc.dll') -join ',') -ne '77,90' { exit 1 }" || (
    echo error: %BUNDLE_DIR%\bin\libtcc.dll is not a PE image >&2
    exit /B 1
)
for %%e in (
    VERSION COPYING RELICENSING MANIFEST
    bin\libtcc.dll lib\libtcc1.a
    include\tccdefs.h include\stddef.h include\stdarg.h include\tcclib.h
    include\_mingw.h include\winapi\windows.h
    lib\msvcrt.def lib\kernel32.def lib\user32.def lib\gdi32.def lib\ws2_32.def
) do (
    if not exist "%BUNDLE_DIR%\%%e" (
        echo error: bundle is missing %%e >&2
        exit /B 1
    )
)

echo.
echo tinycc bundle assembled and verified at: %BUNDLE_DIR%
exit /B 0

:fail
echo error: tinycc bundle build failed ^(see output above^) >&2
exit /B 1
