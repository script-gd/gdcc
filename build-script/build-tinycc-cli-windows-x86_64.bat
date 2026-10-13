@echo off
rem Builds the vendored tinycc CLI (plus the win32 runtime archive and headers it needs to
rem compile and link) from src/main/c/tinycc/ into build/tinycc/windows-x86_64/. This is the
rem test-facing entry point: the Gradle `buildTinyccCli` task runs it before `test` when the CLI
rem is missing or stale (e.g. after `gradlew clean`); tests only look up the result and never
rem spawn this script themselves. Mirrors the upstream win32/build-tcc.bat steps with zig cc as
rem the bootstrap compiler, so no MSYS/configure/make is required on a Windows host.
rem
rem Output: build/tinycc/windows-x86_64/
rem   tcc.exe              the CLI binary (includes the local u8"..." prefix patch)
rem   lib/libtcc1.a        runtime support archive under lib/ — the PE library search path
rem                        expands {B}/lib, so the work tree doubles as a `-B` root
rem   include/             merged tinycc + win32 headers (winapi under include/winapi/)
rem   lib/*.def            import definitions a PE link resolves the runtime against
rem
rem NOTE: verified-by-construction against upstream win32/build-tcc.bat and the cross bundle
rem script's zig cc invocations; first real Windows-host run is pending (tracked under G6).
setlocal
set SCRIPT_DIR=%~dp0
set REPO_ROOT=%SCRIPT_DIR%..
set SRC_DIR=%REPO_ROOT%\src\main\c\tinycc
set WORK_DIR=%REPO_ROOT%\build\tinycc\windows-x86_64
set TINYCC_COMMIT=43c7708b85681a2fd4451c8a541af4494a8919b2
set ZIG_TARGET=x86_64-windows-gnu
set TARGET_DEFS=-DTCC_TARGET_PE -DTCC_TARGET_X86_64

rem ---- preconditions ---------------------------------------------------------
where zig >nul 2>nul || (
    echo error: zig is required ^(used as the bootstrap C compiler via 'zig cc'^) >&2
    exit /B 1
)
findstr /C:"%TINYCC_COMMIT%" "%SRC_DIR%\GDCC_PIN.md" >nul || (
    echo error: %SRC_DIR%\GDCC_PIN.md does not record the pinned commit %TINYCC_COMMIT% — vendored tree and script disagree >&2
    exit /B 1
)

rem ---- pristine build tree ----------------------------------------------------
rem Never configure or build inside the vendored source tree. The verified marker must not
rem survive into a half-rebuilt tree, and a failed removal (locked directory) must not be
rem silently built on top of.
if exist "%WORK_DIR%\cli-verified" del /q "%WORK_DIR%\cli-verified"
if exist "%WORK_DIR%" rmdir /S /Q "%WORK_DIR%"
if exist "%WORK_DIR%" (
    echo error: failed to remove %WORK_DIR% >&2
    exit /B 1
)
mkdir "%WORK_DIR%"
xcopy /s /e /q /y "%SRC_DIR%\." "%WORK_DIR%\" >nul
del /q "%WORK_DIR%\GDCC_PIN.md" 2>nul

cd /d "%WORK_DIR%"

rem ---- config.h ---------------------------------------------------------------
rem configure does not run on a Windows host; the two facts tcc.h needs from config.h are the
rem version string (the target defines arrive via -D on the command line instead).
set /p TCC_VERSION= < VERSION
echo #define TCC_VERSION "%TCC_VERSION%"> config.h

rem ---- build the CLI ------------------------------------------------------------
rem Single-file build: tcc.c includes libtcc.c under the default ONE_SOURCE=1.
rem -fno-sanitize=undefined: zig's default UBSan trips an alignment panic in the PE import
rem generation path (pe_build_imports), same as for the libtcc.dll bundle build.
zig cc -target %ZIG_TARGET% -fno-sanitize=undefined %TARGET_DEFS% tcc.c -o tcc.exe || goto :fail

rem ---- assemble headers and import definitions -----------------------------------
rem Recursive merge: win32/include content lands INSIDE include/ (winapi -> include/winapi/).
rem This must precede the runtime build: crt1.c & co. include <windows.h>/<tchar.h>, and the PE
rem compiler's system header search is {B}/include plus {B}/include/winapi.
mkdir lib 2>nul
xcopy /s /e /q /y "win32\include\." "include\" >nul || goto :fail
copy /y win32\lib\*.def lib\ >nul || goto :fail

rem ---- build the win32 runtime archive ------------------------------------------
rem Mirrors upstream build-tcc.bat's :make_lib (x86_64), compiled by the just-built tcc.
rem Bound-checker/backtrace extras (bcheck, bt-*, runmain) are skipped: the test surface never
rem links with -bt, and leaving them out keeps the bootstrap lean.
mkdir .obj 2>nul
for %%f in (lib\libtcc1.c win32\lib\crt1.c win32\lib\crt1w.c win32\lib\wincrt1.c win32\lib\wincrt1w.c win32\lib\dllcrt1.c win32\lib\dllmain.c win32\lib\winex.c win32\lib\chkstk.S lib\alloca.S lib\alloca-bt.S lib\stdatomic.c lib\atomic.S lib\builtin.c) do (
    .\tcc.exe -B"%WORK_DIR%" -c %%f -o .obj\%%~nf.o || goto :fail
)
.\tcc.exe -ar lib\libtcc1.a .obj\libtcc1.o .obj\crt1.o .obj\crt1w.o .obj\wincrt1.o .obj\wincrt1w.o .obj\dllcrt1.o .obj\dllmain.o .obj\winex.o .obj\chkstk.o .obj\alloca.o .obj\alloca-bt.o .obj\stdatomic.o .obj\atomic.o .obj\builtin.o || goto :fail
rmdir /S /Q .obj

rem ---- verify ---------------------------------------------------------------------
if not exist "%WORK_DIR%\tcc.exe" goto :fail
if not exist "%WORK_DIR%\lib\libtcc1.a" goto :fail
if not exist "%WORK_DIR%\include\tccdefs.h" goto :fail
rem The local u8 prefix patch must be live in the built compiler; a compiler without it is not
rem the vendored one and must never reach the tests.
echo const char *s = u8"patch-probe"; int main(void) { return s[0] != 112 ^|^| s[5] != 45; }> .u8-probe.c
.\tcc.exe -B"%WORK_DIR%" -std=c11 -o .u8-probe.exe .u8-probe.c || goto :u8_fail
.\.u8-probe.exe || goto :u8_fail
del /q .u8-probe.c .u8-probe.exe

rem Written last: the Gradle task declares this marker as an output, so a build killed before
rem this point (binary present, probe not passed) is rebuilt instead of trusted.
type nul > cli-verified

echo.
echo tinycc CLI built and verified at: %WORK_DIR%\tcc.exe
exit /B 0

:u8_fail
echo error: the built tcc rejected u8"..." — the vendored u8 patch is missing >&2
exit /B 1

:fail
echo error: tinycc CLI build failed ^(see output above^) >&2
exit /B 1
