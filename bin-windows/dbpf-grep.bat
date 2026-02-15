@ECHO OFF
SET SCRIPTDIR=%~dp0.
python "%SCRIPTDIR%\..\src\dbpfgrep.py" %*
