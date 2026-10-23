echo. | compile_windows.bat
cd /d "%~dp0.."
start "" javaw -Djava.net.preferIPv4Stack=true -jar "%~dp0..\IdleRSC.jar" 
