using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PuppeteerSharp;

public class AutomationService
{
    private readonly string _baseDir;
    private readonly Action<string> _log;
    private CancellationTokenSource _cts;
    private string _macChangerPath;
    private bool _useBuiltInMacChanger = true;
    private string _interfaceName = "Wi-Fi";

    public AutomationService(string baseDir, Action<string> log)
    {
        _baseDir = baseDir;
        _log = log;
    }

    public void SetMacChangerPath(string path)
    {
        _macChangerPath = path;
    }

    public void SetUseBuiltInMacChanger(bool v)
    {
        _useBuiltInMacChanger = v;
    }

    public void SetInterfaceName(string iface)
    {
        _interfaceName = iface;
    }

    public async Task StartAsync()
    {
        _cts = new CancellationTokenSource();
        await Task.Run(async () =>
        {
            await new BrowserFetcher().DownloadAsync(BrowserFetcher.DefaultRevision);
            while (!_cts.IsCancellationRequested)
            {
                try
                {
                    await RunOnce();
                }
                catch (Exception ex)
                {
                    _log("Error: " + ex.Message);
                }

                // wait an hour before next run
                await Task.Delay(TimeSpan.FromHours(1), _cts.Token).ContinueWith(t => { });
            }
        });
    }

    public void Stop()
    {
        _cts?.Cancel();
    }

    private async Task RunOnce()
    {
        string mac = null;
        // If a custom external mac changer path is configured, try it first
        if (!string.IsNullOrEmpty(_macChangerPath))
        {
            try
            {
                var p = new Process();
                p.StartInfo.FileName = _macChangerPath;
                p.StartInfo.Arguments = "";
                p.StartInfo.RedirectStandardOutput = true;
                p.StartInfo.RedirectStandardError = true;
                p.StartInfo.UseShellExecute = false;
                p.StartInfo.CreateNoWindow = true;
                p.Start();
                var outp = p.StandardOutput.ReadToEnd();
                p.WaitForExit(30000);
                var m = System.Text.RegularExpressions.Regex.Match(outp, "([0-9A-Fa-f]{2}([:-])){5}([0-9A-Fa-f]{2})");
                if (m.Success) mac = m.Value;
                _log("MAC changer output: " + outp);
            }
            catch (Exception ex)
            {
                _log("MAC changer failed: " + ex.Message);
            }
        }

        // If configured, try built-in PowerShell changer (requires admin). It will set the NIC's "Network Address" property and restart adapter.
        if (string.IsNullOrEmpty(mac) && _useBuiltInMacChanger)
        {
            var r = new Random();
            // Ensure first octet is 02 (locally administered unicast). Candidate is 12 hex chars without delimiters.
            var candidateBytes = new string[6];
            candidateBytes[0] = "02";
            for (int i = 1; i < 6; i++) candidateBytes[i] = r.Next(0,256).ToString("X2");
            var candidate = string.Join("", candidateBytes).ToUpper();
            try
            {
                var outp = TryChangeMacViaPowerShell(_interfaceName, candidate);
                if (!string.IsNullOrEmpty(outp))
                {
                    var m = System.Text.RegularExpressions.Regex.Match(outp, "([0-9A-Fa-f]{12})");
                    if (m.Success)
                    {
                        var macRaw = m.Value;
                        // format with colons
                        mac = string.Join(":", Enumerable.Range(0,6).Select(i => macRaw.Substring(i*2,2)));
                        _log("Built-in PowerShell MAC set: " + mac);
                    }
                }
            }
            catch (Exception ex)
            {
                _log("Built-in MAC changer failed: " + ex.Message);
            }
        }

        if (string.IsNullOrEmpty(mac))
        {
            var r = new Random();
            mac = string.Join(":", Enumerable.Range(0,6).Select(i => r.Next(0,256).ToString("X2")));
            _log("Using simulated MAC: " + mac);
        }

        var launchOptions = new LaunchOptions { Headless = true };
        using var browser = await Puppeteer.LaunchAsync(launchOptions);
        var context = await browser.CreateIncognitoBrowserContextAsync();
        var page = await context.NewPageAsync();

        var preload = Path.Combine(_baseDir, "preload.js");
        if (File.Exists(preload))
        {
            var preloadJs = File.ReadAllText(preload);
            await page.EvaluateFunctionOnNewDocumentAsync(preloadJs);
        }

        await page.SetRequestInterceptionAsync(true);
        page.Request += async (sender, e) => { try { await e.Request.ContinueAsync(); } catch { } };

        var portal = $"http://cwifi-new.cox.com/?mac-address={mac}&ap-mac=70:03:7E:E2:F4:10&ssid=CoxWiFi&vlan=103&nas-id=BTNRWAGB01.at.at.cox.net&block=false&unique=$HASH";
        _log("Navigating to portal: " + portal);
        await page.GoToAsync(portal, new NavigationOptions { WaitUntil = new[] { WaitUntilNavigation.Networkidle0 }, Timeout = 60000 });
        var landing = Path.Combine(_baseDir, "landing.jpeg");
        await page.ScreenshotAsync(landing, new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
        _log("Saved landing screenshot: " + landing);

        // similar automation steps as JS
        try
        {
            await page.WaitForSelectorAsync("#signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton", new WaitForSelectorOptions { Timeout = 30000 });
        }
        catch
        {
            var selErr = Path.Combine(_baseDir, "selector-error.jpeg");
            await page.ScreenshotAsync(selErr, new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
            _log("Selector error, saved: " + selErr);
            return;
        }

        await page.ClickAsync("table #trial_request_voucher_form_email");
        var name = "John Doe";
        var parts = name.Split(' ');
        var firstName = parts[0];
        var lastName = parts.Length > 1 ? parts[1] : "Doe";
        var domains = new[] { "gmail.com", "yahoo.com", "outlook.com", "live.com", "aol.com" };
        var email = (new Random().Next(0,2) == 0 ? firstName + "." + lastName : lastName + "." + firstName) + "@" + domains[new Random().Next(domains.Length)];
        await page.TypeAsync("table #trial_request_voucher_form_email", email);
        await page.WaitForSelectorAsync(".decisionBlock > table > tbody > tr > .top:nth-child(2)");
        await page.ClickAsync(".decisionBlock > table > tbody > tr > .top:nth-child(2)");
        await page.WaitForSelectorAsync("table #trial_request_voucher_form_serviceTerms");
        await page.ClickAsync("table #trial_request_voucher_form_serviceTerms");
        await page.Keyboard.DownAsync("Tab");
        await page.Keyboard.DownAsync("Tab");
        await page.Keyboard.PressAsync("Enter");
        await page.WaitForNavigationAsync(new NavigationOptions { Timeout = 60000 });

        var pageText = await page.EvaluateExpressionAsync<string>("(function(){var s=window.getSelection();s.removeAllRanges();var r=document.createRange();r.selectNode(document.body);s.addRange(r);var c=s.toString();s.removeAllRanges();return c; })();");
        if (pageText.ToLower().Contains("you are now connected"))
        {
            var res = Path.Combine(_baseDir, "result.jpeg");
            await page.ScreenshotAsync(res, new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
            _log("Connected, saved result: " + res);
        }
        else
        {
            var err = Path.Combine(_baseDir, "error-result.jpeg");
            await page.ScreenshotAsync(err, new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
            _log("Not connected, saved error: " + err);
        }

        await browser.CloseAsync();
    }

    private string TryChangeMacViaPowerShell(string iface, string macNoDelims)
    {
        // macNoDelims should be 12 hex chars
        try
        {
            var mac = macNoDelims.Replace(":", "").Replace("-", "").ToUpper();
            if (mac.Length != 12) return null;
            // PowerShell commands to set the Network Address property and restart adapter
            var ps = $"Set-NetAdapterAdvancedProperty -Name '{iface}' -DisplayName 'Network Address' -DisplayValue '{mac}'; Disable-NetAdapter -Name '{iface}' -Confirm:$false; Start-Sleep -Seconds 2; Enable-NetAdapter -Name '{iface}' -Confirm:$false; Write-Output '{mac}';";
            var psi = new ProcessStartInfo("powershell", $"-NoProfile -ExecutionPolicy Bypass -Command \"{ps}\"")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            var p = Process.Start(psi);
            var outp = p.StandardOutput.ReadToEnd();
            var err = p.StandardError.ReadToEnd();
            p.WaitForExit(30000);
            if (!string.IsNullOrEmpty(err)) _log("PowerShell stderr: " + err);
            return outp?.Trim();
        }
        catch (Exception ex)
        {
            _log("PowerShell change MAC exception: " + ex.Message);
            return null;
        }
    }
}
