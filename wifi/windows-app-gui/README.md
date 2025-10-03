FreeWifi GUI (WPF)

This is a simple WPF application that wraps the captive portal automation flow.

How to run

- Requires .NET 7 SDK and Visual Studio (or `dotnet` CLI) on Windows.
- Open `windows-app-gui/FreeWifiGui.csproj` in Visual Studio and run.

Features

- Start/Stop automation loop (runs every hour)
- Change MAC via external tool (set path to Technitium or a PowerShell script)
- Live logs and screenshots are saved in the app folder

Notes

- Changing NIC MAC may require admin privileges. The app can call external tools (path selected via dialog) to handle MAC changes; those tools must handle elevation.
