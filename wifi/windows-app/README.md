Windows .NET port of free-wifi

What this is

- A .NET console app using PuppeteerSharp that reproduces the browser automation flow from `free-wifi.js`.
- It attempts to run an optional `Set-Mac.ps1` PowerShell helper to change the NIC MAC (if present and run as admin). If not available, it generates a simulated MAC used only in the captive-portal URL.

How to build

- Install .NET 7 SDK.
- Open a terminal in `windows-app/` and run:

  dotnet build
  dotnet run -- "Wi-Fi" --debug --timeout 60000

Notes about MAC spoofing on Windows

- Changing NIC MAC addresses on Windows often requires admin privileges and may not be supported by some drivers.
- If you want true MAC spoofing, provide a tested `Set-Mac.ps1` that performs the change, and run the app as administrator.

