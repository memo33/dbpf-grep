@ECHO OFF
SET SCRIPTDIR=%~dp0.
python "%SCRIPTDIR%\..\lib\dbpfgrep.py" %*
