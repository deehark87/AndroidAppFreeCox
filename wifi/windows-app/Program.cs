using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using PuppeteerSharp;
using System.Diagnostics;

class Program
{
    static async Task<int> Main(string[] args)
    {
        var iface = args.Length > 0 ? args[0] : "Wi-Fi";
        var debug = args.Contains("--debug");
        var timeout = 60000;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--timeout" && i + 1 < args.Length)
            {
                int.TryParse(args[i+1], out timeout);
            }
        }

        string mac = null;
        try
        {
            // Try to run Set-Mac.ps1 if present
            var scriptPath = Path.Combine(AppContext.BaseDirectory, "Set-Mac.ps1");
            if (File.Exists(scriptPath))
            {
                var psi = new ProcessStartInfo("powershell", $"-ExecutionPolicy Bypass -File \"{scriptPath}\" -InterfaceName \"{iface}\"")
                {
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                var p = Process.Start(psi);
                var outStr = await p.StandardOutput.ReadToEndAsync();
                var errStr = await p.StandardError.ReadToEndAsync();
                p.WaitForExit(30000);
                if (!string.IsNullOrEmpty(errStr) && debug) Console.WriteLine("Set-Mac stderr: " + errStr);
                var m = Regex.Match(outStr, "([0-9A-Fa-f]{2}([:-])){5}([0-9A-Fa-f]{2})");
                if (m.Success) mac = m.Value;
            }
        }
        catch (Exception ex)
        {
            if (debug) Console.WriteLine("Set-Mac failed: " + ex.Message);
        }

        if (string.IsNullOrEmpty(mac))
        {
            // generate simulated MAC with first octet 02 (locally administered unicast)
            var r = new Random();
            var parts = new string[6];
            parts[0] = "02";
            for (int i = 1; i < 6; i++) parts[i] = r.Next(0,256).ToString("X2");
            mac = string.Join(":", parts).ToUpper();
            Console.WriteLine("Using simulated MAC: " + mac);
        }

        await new BrowserFetcher().DownloadAsync(BrowserFetcher.DefaultRevision);
        var launchOptions = new LaunchOptions
        {
            Headless = true,
            Args = new[] { "--no-sandbox", "--disable-setuid-sandbox" }
        };

        await Task.Delay(timeout);

        using var browser = await Puppeteer.LaunchAsync(launchOptions);
        var context = await browser.CreateIncognitoBrowserContextAsync();
        var page = await context.NewPageAsync();

        var preloadFile = Path.Combine(AppContext.BaseDirectory, "preload.js");
        if (File.Exists(preloadFile))
        {
            var preload = File.ReadAllText(preloadFile);
            await page.EvaluateFunctionOnNewDocumentAsync(preload);
        }

        await page.SetRequestInterceptionAsync(true);
        page.Request += async (sender, e) =>
        {
            try { await e.Request.ContinueAsync(); } catch { }
        };

        var portal = $"http://cwifi-new.cox.com/?mac-address={mac}&ap-mac=70:03:7E:E2:F4:10&ssid=CoxWiFi&vlan=103&nas-id=BTNRWAGB01.at.at.cox.net&block=false&unique=$HASH";

        await page.GoToAsync(portal, new NavigationOptions { WaitUntil = new[] { WaitUntilNavigation.Networkidle0 }, Timeout = timeout });
        await page.ScreenshotAsync(Path.Combine(AppContext.BaseDirectory, "landing.jpeg"), new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });

        if (debug)
        {
            var content = await page.GetContentAsync();
            Console.WriteLine(content.Substring(0, Math.Min(1000, content.Length)));
            var pageText = await page.EvaluateExpressionAsync<string>("document.body.innerText");
            Console.WriteLine(pageText);
        }

        // Try the selector; if not found, capture selector-error
        try
        {
            await page.WaitForSelectorAsync("#signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton", new WaitForSelectorOptions { Timeout = 30000 });
        }
        catch (Exception)
        {
            await page.ScreenshotAsync(Path.Combine(AppContext.BaseDirectory, "selector-error.jpeg"), new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
            throw;
        }

        await page.ClickAsync("table #trial_request_voucher_form_email");
        var name = "";
        try { name = new RandomNameGenerator.NameGenerator().Generate(); } catch { name = "John Doe"; }
        var parts = name.Split(' ');
        var firstName = parts.Length > 0 ? parts[0] : "John";
        var lastName = parts.Length > 1 ? parts[1] : "Doe";
        var domains = new[] { "gmail.com", "yahoo.com", "outlook.com", "live.com", "aol.com" };
        var email = (new Random().Next(0,2) == 0 ? firstName + "." + lastName : lastName + "." + firstName) + "@" + domains[new Random().Next(domains.Length)];

        await page.TypeAsync("table #trial_request_voucher_form_email", email, new TypeOptions { Delay = rand(100,300) });

        await page.WaitForSelectorAsync(".decisionBlock > table > tbody > tr > .top:nth-child(2)");
        await page.ClickAsync(".decisionBlock > table > tbody > tr > .top:nth-child(2)");
        await page.WaitForSelectorAsync("table #trial_request_voucher_form_serviceTerms");
        await page.ClickAsync("table #trial_request_voucher_form_serviceTerms");

        await page.Keyboard.DownAsync("Tab");
        await page.Keyboard.DownAsync("Tab");
        await page.Keyboard.PressAsync("Enter");

        await page.WaitForNavigationAsync(new NavigationOptions { Timeout = timeout });

        var pageText = await page.EvaluateExpressionAsync<string>("(function(){var s=window.getSelection();s.removeAllRanges();var r=document.createRange();r.selectNode(document.body);s.addRange(r);var c=s.toString();s.removeAllRanges();return c; })();");

        if (debug) Console.WriteLine(pageText);

        if (pageText.ToLower().Contains("you are now connected"))
        {
            var t = DateTime.Now.ToString();
            Console.WriteLine("Wifi Connected Successfully " + t);
            await page.ScreenshotAsync(Path.Combine(AppContext.BaseDirectory, "result.jpeg"), new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
        }
        else
        {
            await page.ScreenshotAsync(Path.Combine(AppContext.BaseDirectory, "error-result.jpeg"), new ScreenshotOptions { Type = ScreenshotType.Jpeg, Quality = 100 });
        }

        await browser.CloseAsync();

        // rerun after an hour
        await Task.Delay(60000 * 60);
        return 0;
    }
}
