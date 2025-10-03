const puppeteer = require("puppeteer");
const { exec } = require("child_process");
const util = require("util");
const execAsync = util.promisify(exec);
const random_name = require("node-random-name");
const random_useragent = require("random-useragent");
const fs = require("fs");
const path = require("path");
const yargs = require("yargs/yargs");
const { hideBin } = require("yargs/helpers");

const argv = yargs(hideBin(process.argv))
.option("iface", {
  alias: "i",
  describe: "Interface to use (optional on Windows)",
  type: "string",
})
.option("debug", {
  alias: "d",
  type: "boolean",
  description: "Run with debug output",
})
.option("timeout", {
  alias: "t",
  default: 60000,
    description: "Time to wait for page loads",
})
.argv;

const rand = (min, max) => {
  return Math.floor(Math.random() * (max - min + 1)) + min;
};

const domains = [
  "gmail.com",
  "yahoo.com",
  "outlook.com",
  "live.com",
  "aol.com",
];

const emailMixer = (firstName, lastName) => {
  let first = rand(0, 1)
    ? firstName + "." + lastName
    : lastName + "." + firstName;

  return `${first}@${domains[Math.floor(Math.random() * domains.length)]}`;
};

function generateRandomMac() {
  const hex = "0123456789ABCDEF";
  const parts = [];
  // Ensure locally administered unicast address by setting first octet to 02
  parts.push('02');
  for (let i = 1; i < 6; i++) {
    parts.push(hex[Math.floor(Math.random() * 16)] + hex[Math.floor(Math.random() * 16)]);
  }
  return parts.join(":").toUpperCase();
}

async function tryRunPowerShellSetMac(iface) {
  // If a helper PowerShell script exists (Set-Mac.ps1), try to run it.
  // The script is expected to print a line containing the new MAC address.
  const scriptPath = path.resolve(__dirname, "Set-Mac.ps1");
  if (!fs.existsSync(scriptPath)) return null;

  try {
    // Build command. We don't force elevation here; if the script needs admin it will fail.
    const cmd = `powershell -ExecutionPolicy Bypass -File "${scriptPath}" -InterfaceName "${iface || "Wi-Fi"}"`;
    const { stdout, stderr } = await execAsync(cmd, { timeout: 30000 });
    if (stderr && stderr.trim()) {
      console.warn("Set-Mac.ps1 stderr:", stderr);
    }
    // Try to extract MAC-like string from stdout
    const macMatch = stdout.match(/([0-9A-Fa-f]{2}([:-])){5}([0-9A-Fa-f]{2})/);
    if (macMatch) return macMatch[0];
    return null;
  } catch (e) {
    if (argv.debug) console.warn("PowerShell Set-Mac failed:", e && e.message);
    return null;
  }
}

(async function run() {
  const name = random_name();
  const firstName = name.split(" ")[0];
  const lastName = name.split(" ")[1];

  const agent = random_useragent.getRandom(function (ua) {
    return !ua.userAgent.includes("Mobile") && ua.userAgent.includes("Windows");
  });

  const args = [
    "--user-agent=" + agent,
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--disable-infobars",
    "--window-position=0,0",
    "--ignore-certifcate-errors",
    "--ignore-certifcate-errors-spki-list",
  ];

  const options = {
    args,
    headless: true,
    ignoreHTTPSErrors: true,
  };

  // Attempt to run a PowerShell helper to set MAC (optional). If it fails, fall back to a generated MAC used only in the portal URL.
  let mac = null;
  const attemptedMac = await tryRunPowerShellSetMac(argv.iface).catch((e) => {
    if (argv.debug) console.warn('PowerShell attempt error', e && e.message);
    return null;
  });
  if (attemptedMac) {
    mac = attemptedMac;
    console.log('Set MAC via PowerShell helper:', mac);
  } else {
    mac = generateRandomMac();
    console.log('No PowerShell helper found or failed; using simulated MAC for portal URL:', mac);
  }

  // Delay before browsing to allow any network changes (best-effort)
  await new Promise((r) => setTimeout(r, argv.timeout));

  const browser = await puppeteer.launch(options);

  try {
    const context = await browser.createBrowserContext();
    const page = await context.newPage();

    const preloadFile = fs.readFileSync(path.resolve(__dirname, './preload.js'), 'utf8');
    await page.evaluateOnNewDocument(preloadFile);
    await page.setRequestInterception(true);
    page.on('request', (request) => {
      if (!request.isInterceptResolutionHandled()) request.continue();
    });

    const portalUrl = `http://cwifi-new.cox.com/?mac-address=${mac}&ap-mac=70:03:7E:E2:F4:10&ssid=CoxWiFi&vlan=103&nas-id=BTNRWAGB01.at.at.cox.net&block=false&unique=$HASH`;

    await page.goto(portalUrl, { waitUntil: 'networkidle2', timeout: argv.timeout });

    await page.screenshot({ path: path.resolve(__dirname, 'landing.jpeg'), type: 'jpeg', quality: 100 });

    if (argv.debug) {
      const pageContent = await page.content();
      console.log('Page HTML:', pageContent.substring(0, 1000));
      const pageText = await page.evaluate(() => document.body.innerText);
      console.log('Page Text:', pageText);
    }

    const buttons = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button, input[type="submit"], .button, [class*="button"]'));
      return btns.map(b => ({ tag: b.tagName, class: b.className, id: b.id, text: b.innerText || b.value }));
    });

    if (argv.debug) console.log('Found buttons:', JSON.stringify(buttons, null, 2));

    await page.waitForSelector('#signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton', { timeout: 30000 }).catch(async (err) => {
      console.log('Original selector not found, taking error screenshot');
      await page.screenshot({ path: path.resolve(__dirname, 'selector-error.jpeg'), type: 'jpeg', quality: 100 });
      throw err;
    });

    await page.click('table #trial_request_voucher_form_email');
    await page.type('table #trial_request_voucher_form_email', emailMixer(firstName, lastName), { delay: rand(100, 300) });

    await page.waitForSelector('.decisionBlock > table > tbody > tr > .top:nth-child(2)');
    await page.click('.decisionBlock > table > tbody > tr > .top:nth-child(2)');

    await page.waitForSelector('table #trial_request_voucher_form_serviceTerms');
    await page.click('table #trial_request_voucher_form_serviceTerms');

    await page.keyboard.down('Tab');
    await page.keyboard.down('Tab');
    await page.keyboard.press('Enter');

    await page.waitForNavigation({ timeout: argv.timeout });

    const pageText = await page.evaluate(() => {
      return (function () {
        var s = window.getSelection();
        s.removeAllRanges();
        var r = document.createRange();
        r.selectNode(document.body);
        s.addRange(r);
        var c = s.toString();
        s.removeAllRanges();
        return c;
      })();
    });

    if (argv.debug) console.log(pageText);

    if (pageText.toLowerCase().includes('you are now connected')) {
      const t = new Date().toLocaleString();
      console.log('Wifi Connected Successfully', t);
      await page.screenshot({ path: path.resolve(__dirname, 'result.jpeg'), type: 'jpeg', quality: 100 });
    } else {
      await page.screenshot({ path: path.resolve(__dirname, 'error-result.jpeg'), type: 'jpeg', quality: 100 });
    }

  } catch (err) {
    console.error('Error during automation:', err && err.message);
  } finally {
    try { await browser.close(); } catch (e) {}
  }

  // Loop like original script: rerun after an hour
  setTimeout(run, 60000 * 60);

})();
