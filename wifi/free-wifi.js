const puppeteer = require("puppeteer");
const sh = require("shelljs");
const random_name = require("node-random-name");
const random_useragent = require("random-useragent");
const fs = require("fs");
const path = require("path");
const yargs = require("yargs/yargs");
const { hideBin } = require("yargs/helpers");

const argv = yargs(hideBin(process.argv))
.option("iface", {
  alias: "i",
  describe: "Interface to use",
  type: "string",
  demandOption: true,
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
.option("no-intercept", {
  type: "boolean",
  default: false,
  description: "Disable Puppeteer request interception (may help SPA resource loading)",
})
.option('headful', {
  type: 'boolean',
  default: false,
  description: 'Launch browser in headful (non-headless) mode for interactive debugging',
})
.option('slowmo', {
  type: 'number',
  description: 'Slow down Puppeteer operations by given ms (useful with --headful)',
  default: 0,
})
.argv;

const rand = (min, max) => {
  return Math.floor(Math.random() * (max - min + 1)) + min;
};

const domains = [
  "gmail.com",
  "yahoo.com",
  "outlook.com",
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
    headless: !argv.headful,
    slowMo: argv.slowmo && Number(argv.slowmo) > 0 ? Number(argv.slowmo) : undefined,
    ignoreHTTPSErrors: true,
  };

  sh.exec(
    `bash ./Macchangerizer.sh ${argv.iface} && sleep 10`,
    async (code, output) => {
      var macParts = output.match(/(?<=New MAC:       \s*).*?(?=\s* )/gs);

    const mac = macParts[0];

    await new Promise((r) => setTimeout(r, argv.timeout));

      const browser = await puppeteer.launch(options);

      const context = await browser.createBrowserContext(); // This is the modern equivalent
      const page = await context.newPage();

      const preloadFile = fs.readFileSync("./preload.js", "utf8");
      await page.evaluateOnNewDocument(preloadFile);
      // Wire page console messages and response logging for better diagnostics
      page.on('console', msg => {
        try { if (argv.debug) console.log('PAGE LOG:', msg.text()); } catch (e) {}
      });
      page.on('response', async (res) => {
        try {
          if (argv.debug && res && res.url()) console.log('RESPONSE', res.status(), res.url());
        } catch (e) {}
      });

      // Request interception can interfere with SPA loading; allow disabling via --no-intercept
      if (!argv['no-intercept']) {
        await page.setRequestInterception(true);
        page.on('request', (request) => {
          request.continue().catch((e) => {
            if (argv.debug) console.warn('Request continue failed for', request.url(), e && e.message);
          });
        });

        // Log failed requests to help diagnose blocked/filtered requests
        page.on('requestfailed', (req) => {
          if (argv.debug) {
            const failure = req.failure && (typeof req.failure === 'function' ? req.failure() : req.failure) || {};
            console.warn('Request failed:', req.url(), failure.errorText || failure);
          }
        });
      } else {
        if (argv.debug) console.log('--no-intercept set: request interception disabled to let SPA load');
      }
      try {
        await page.goto(
          `https://uwp-wifi-access-portal.cox.com/splash?mac-address=${mac}&ap-mac=3C:82:C0:F6:DA:24&ssid=CoxWiFi&vlan=103&nas-id=NRFKWAGB01.at.at.cox.net&block=false&unique=$HASH`,
          {
            waitUntil: "networkidle2",
            timeout: 60000,
          }
        );
      } catch (err) {
        console.error('Navigation failed:', err && err.message);
        if (argv.debug) console.error(err);
        // Take a diagnostic screenshot and continue to allow further debugging
        try {
          await page.screenshot({ path: path.resolve(__dirname) + '/nav-error.jpeg', type: 'jpeg', quality: 80 });
        } catch (e) {}
        // Rethrow so outer catch can handle loop logic, or you can choose to return here
        throw err;
      }

      await page.screenshot({
        path: path.resolve(__dirname) + "/landing.jpeg",
                            type: "jpeg",
                            quality: 100,
      });

      // Debug: Log the page content to see what we're actually getting
      if (argv.debug) {
        const pageContent = await page.content();
        console.log("Page HTML:", pageContent.substring(0, 1000)); // First 1000 chars

        const pageText = await page.evaluate(() => document.body.innerText);
        console.log("Page Text:", pageText);
      }

      // Try to find ANY buttons on the page
      const buttons = await page.evaluate(() => {
        const btns = Array.from(document.querySelectorAll('button, input[type="submit"], .button, [class*="button"]'));
        return btns.map(b => ({
          tag: b.tagName,
          class: b.className,
          id: b.id,
          text: b.innerText || b.value
        }));
      });

      if (argv.debug) {
        console.log("Found buttons:", JSON.stringify(buttons, null, 2));
      }

      // Original selector that's failing. Add fallbacks: XPath/text search and input focus fallback.
      const primarySelector = "#signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton";
      let found = false;
      try {
        await page.waitForSelector(primarySelector, { timeout: 30000 });
        found = true;
        await page.click(primarySelector);
      } catch (err) {
        if (argv.debug) console.warn('Primary selector not found, trying fallbacks...');

        // Log candidate buttons to help diagnosis
        const candidates = await page.evaluate(() => {
          const els = Array.from(document.querySelectorAll('button, input[type="submit"], a, [role="button"], .button'));
          return els.map(e => ({ tag: e.tagName, id: e.id, class: e.className, text: e.innerText && e.innerText.trim().slice(0,200) }));
        });
        if (argv.debug) console.log('Candidate clickable elements:', JSON.stringify(candidates, null, 2));

        // Try DOM/text-based fallback: prefer 'register'/'get'/'trial' CTAs over 'sign in'
        const textFallbackResult = await page.evaluate(() => {
          const keywords = ['register','get a free','get free','get','trial','free','access','connect','start','continue','sign in','login'];
          // search a set of likely clickable elements
          const candidates = Array.from(document.querySelectorAll('button, a, input[type="submit"], [role="button"], .button'));
          // Prioritize by keyword order: for each keyword, scan candidates and click the first matching element
          for (let k of keywords) {
            for (let el of candidates) {
              const text = (el.innerText || el.value || '').toLowerCase();
              if (text.includes(k)) {
                try { el.click(); return { clicked: true, tag: el.tagName, text: text.trim().slice(0,200), keyword: k }; } catch (e) { return { clicked: false, err: String(e) }; }
              }
            }
          }
          return { clicked: false };
        });
        if (textFallbackResult && textFallbackResult.clicked) {
          found = true;
          if (argv.debug) console.log('Clicked text-matching fallback:', textFallbackResult.tag, textFallbackResult.text, 'keyword:', textFallbackResult.keyword);
        } else if (textFallbackResult && textFallbackResult.err && argv.debug) {
          console.warn('Text-fallback click threw:', textFallbackResult.err);
        }

        // If still not found, try clicking candidate elements that match CTA keywords
        if (!found) {
          // Preferred CTA order: Register / Get Trial first, then other CTAs, then Sign In as a last resort
          const ctaKeywords = ['register','get a free','get free','get','trial','free','buy','start','continue','access','connect','enter passcode','promo','sign in','login'];
          const clicked = await page.evaluate((keywords) => {
            const els = Array.from(document.querySelectorAll('button, input[type="submit"], a, [role="button"], .button'));
            // For each keyword in priority order, find the first element that matches and click it
            for (let k of keywords) {
              for (let el of els) {
                const text = (el.innerText || el.value || '').toLowerCase();
                if (text.includes(k)) {
                  try { el.click(); return { clicked: true, tag: el.tagName, text: text.trim().slice(0,200), keyword: k }; } catch (e) { return { clicked: false, err: String(e) }; }
                }
              }
            }
            return { clicked: false };
          }, ctaKeywords);
          if (clicked && clicked.clicked) {
            found = true;
            if (argv.debug) console.log('Clicked CTA candidate:', clicked.tag, clicked.text, 'keyword:', clicked.keyword);
          } else if (clicked && clicked.err && argv.debug) {
            console.warn('Candidate click threw:', clicked.err);
          }
        }

        // If still not found, try focusing the email input as a last resort
        if (!found) {
          const emailInput = await page.$('table #trial_request_voucher_form_email');
          if (emailInput) {
            await emailInput.focus();
            found = true;
            if (argv.debug) console.log('Focused email input as last-resort fallback');
          }
        }

        if (!found) {
          // Save more diagnostics: screenshot and full HTML dump
          console.log('No suitable fallback found, saving diagnostics...');
          await page.screenshot({ path: path.resolve(__dirname) + '/selector-error.jpeg', type: 'jpeg', quality: 100 });
          try {
            const html = await page.content();
            const htmlPath = path.resolve(__dirname, 'selector-error.html');
            fs.writeFileSync(htmlPath, html, 'utf8');
            if (argv.debug) console.log('Saved selector HTML to', htmlPath);
          } catch (e) {
            console.warn('Failed to write selector-error.html:', e && e.message);
          }
          throw err;
        }
      }

      // After clicking the CTA there may be a navigation which destroys the execution context.
      // Wait a short time for navigation to start/finish and then try multiple input selectors.
      try {
        await Promise.race([
          page.waitForNavigation({ timeout: 3000 }).catch(() => {}),
          new Promise((r) => setTimeout(r, 800)),
        ]);

        const emailSelectors = [
          'table #trial_request_voucher_form_email',
          'input[type="email"]',
          'input[name="email"]',
          'input[id*="email"]',
          'input[placeholder*="Email"]',
          'input[placeholder*="email"]'
        ];

        let typed = false;
        for (const sel of emailSelectors) {
          try {
            const el = await page.$(sel);
            if (el) {
              await page.type(sel, emailMixer(firstName, lastName), { delay: rand(100, 300) });
              typed = true;
              if (argv.debug) console.log('Typed into', sel);
              break;
            }
          } catch (e) {
            if (argv.debug) console.warn('Typing into', sel, 'failed', e && e.message);
          }
        }

        if (!typed) {
          if (argv.debug) console.warn('No email input found after click; attempting direct navigation to trial page');
          // Attempt direct navigation to known trial path (avoid relying on client-side router)
          const trialUrl = `https://uwp-wifi-access-portal.cox.com/subscribers/trial?mac-address=${mac}&ap-mac=3C:82:C0:F6:DA:24&ssid=CoxWiFi&vlan=103&nas-id=NRFKWAGB01.at.at.cox.net&block=false&unique=$HASH`;
          try {
            await page.goto(trialUrl, { waitUntil: 'networkidle2', timeout: 15000 });
            if (argv.debug) console.log('Navigated directly to trial page to load form');

            // Wait for the client-side app to render: either an email input appears or the <app-core> mounts children.
            // Give the SPA more time (up to 30s) and poll every 500ms.
            try {
              const start = Date.now();
              const timeout = 30000;
              const diagInterval = 2000; // save diagnostics every 2s during poll
              let lastDiag = 0;
              while (Date.now() - start < timeout) {
                const has = await page.evaluate(() => {
                  try {
                    if (document.querySelector('input[type="email"], input[name*="email"], #trial_request_voucher_form_email')) return true;
                    const appCore = document.querySelector('app-core');
                    return appCore && appCore.querySelectorAll('*').length > 0;
                  } catch (e) { return false; }
                });
                const elapsed = Date.now() - start;
                if (elapsed - lastDiag >= diagInterval) {
                  lastDiag = elapsed;
                  // save periodic diagnostics to help create a timelapse of client render
                  try {
                    const snapPath = path.resolve(__dirname, `poll-${Math.floor(elapsed/1000)}s.jpeg`);
                    await page.screenshot({ path: snapPath, type: 'jpeg', quality: 60 });
                    if (argv.debug) console.log('Saved polling screenshot', snapPath);
                    const html = await page.content();
                    const htmlPath = path.resolve(__dirname, `poll-${Math.floor(elapsed/1000)}s.html`);
                    fs.writeFileSync(htmlPath, html, 'utf8');
                    if (argv.debug) console.log('Saved polling HTML', htmlPath);
                  } catch (e) { if (argv.debug) console.warn('Failed to save polling diagnostics', e && e.message); }
                }
                if (has) break;
                await new Promise((r) => setTimeout(r, 500));
              }
            } catch (waitErr) {
              if (argv.debug) console.warn('Error while polling for client render after direct navigation', waitErr && waitErr.message);
            }

            // If polling timed out and we still don't have the form, attempt to nudge the SPA via client-side routing events
            const hasAfterPoll = await page.evaluate(() => {
              try {
                return !!(document.querySelector('input[type="email"], input[name*="email"], #trial_request_voucher_form_email'));
              } catch (e) { return false; }
            });
            if (!hasAfterPoll) {
              if (argv.debug) console.log('Polling timed out without finding form; attempting client-side routing triggers');
              try {
                // try pushState to the trial route and dispatch a popstate event
                await page.evaluate(() => {
                  try {
                    const url = '/subscribers/trial';
                    history.pushState({}, '', url);
                    window.dispatchEvent(new PopStateEvent('popstate'));
                    // also try a hash change trick
                    const oldHash = location.hash;
                    location.hash = '#force-route-' + Date.now();
                    setTimeout(() => { location.hash = oldHash; }, 50);
                  } catch (e) { console.log('client trigger error', String(e)); }
                });
                // give the app some time to react to the route change
                const start2 = Date.now();
                const timeout2 = 10000; // extra 10s
                while (Date.now() - start2 < timeout2) {
                  const has2 = await page.evaluate(() => {
                    try { return !!(document.querySelector('input[type="email"], input[name*="email"], #trial_request_voucher_form_email')); } catch (e) { return false; }
                  });
                  if (has2) break;
                  await new Promise((r) => setTimeout(r, 500));
                }
              } catch (triggerErr) {
                if (argv.debug) console.warn('Client-side routing trigger failed', triggerErr && triggerErr.message);
              }
            }

            // Try to find email input again
            for (const sel of ['table #trial_request_voucher_form_email','input[type="email"]','input[name="email"]','input[id*="email"]']) {
              try {
                const el2 = await page.$(sel);
                if (el2) {
                  await page.type(sel, emailMixer(firstName, lastName), { delay: rand(100, 300) });
                  typed = true;
                  if (argv.debug) console.log('Typed into', sel, 'after direct navigation');
                  break;
                }
              } catch (e) { if (argv.debug) console.warn('Retry typing failed for', sel, e && e.message); }
            }
          } catch (navErr) {
            if (argv.debug) console.warn('Direct navigation to trial page failed', navErr && navErr.message);
          }

          if (!typed) {
            if (argv.debug) console.warn('Still no email input found after direct navigation; saving full HTML for diagnostics');
            await page.screenshot({ path: path.resolve(__dirname) + '/selector-error-after-click.jpeg', type: 'jpeg', quality: 100 });
            try {
              const html = await page.content();
              const htmlPath = path.resolve(__dirname, 'selector-error-after-click.html');
              fs.writeFileSync(htmlPath, html, 'utf8');
              if (argv.debug) console.log('Saved post-click HTML to', htmlPath);
            } catch (e) {
              console.warn('Failed to write post-click HTML:', e && e.message);
            }
            throw new Error('Email input not found after CTA click');
          }
        }
      } catch (err) {
        // If execution context was destroyed due to navigation, wait and retry once
        if (err && /Execution context was destroyed/.test(err.message)) {
          if (argv.debug) console.warn('Execution context destroyed, retrying after short wait');
          await new Promise((r) => setTimeout(r, 1500));
          try {
            await page.type('input[type="email"]', emailMixer(firstName, lastName), { delay: rand(100, 300) });
          } catch (e) {
            console.error('Retry typing failed:', e && e.message);
            throw e;
          }
        } else {
          throw err;
        }
      }

      // Click the decision button. The original selector may be brittle; try fallbacks.
      const decisionSelector = ".decisionBlock > table > tbody > tr > .top:nth-child(2)";
      let decisionClicked = false;
      try {
        await page.waitForSelector(decisionSelector, { timeout: 10000 });
        await page.click(decisionSelector);
        decisionClicked = true;
      } catch (e) {
        if (argv.debug) console.warn('Decision selector not found, trying button-text fallbacks...');
        // Prefer CTAs that advance registration/pax flow
        const decisionKeywords = ['register','get a free','get free','get','trial','continue','next','request','submit','get access','get started','start'];
        const decisionResult = await page.evaluate((keywords) => {
          const els = Array.from(document.querySelectorAll('button, input[type="submit"], a, [role="button"], .button'));
          for (let k of keywords) {
            for (let el of els) {
              const text = (el.innerText || el.value || '').toLowerCase();
              if (text.includes(k)) {
                try { el.click(); return { clicked: true, tag: el.tagName, text: text.trim().slice(0,200), keyword: k }; } catch (err) { return { clicked: false, err: String(err) }; }
              }
            }
          }
          return { clicked: false };
        }, decisionKeywords);
        if (decisionResult && decisionResult.clicked) {
          decisionClicked = true;
          if (argv.debug) console.log('Clicked decision CTA:', decisionResult.tag, decisionResult.text, 'keyword:', decisionResult.keyword);
        }
        // If still not clicked, try to submit the nearest form (if email input is inside one)
        if (!decisionClicked) {
          try {
            const submitted = await page.evaluate(() => {
              const input = document.querySelector('input[type="email"], input[name*="email"], input[id*="email"]');
              if (input) {
                const form = input.closest('form');
                if (form) { form.submit(); return true; }
              }
              return false;
            });
            if (submitted) {
              decisionClicked = true;
              if (argv.debug) console.log('Submitted nearest form as decision fallback');
            }
          } catch (err) {
            if (argv.debug) console.warn('Form submit fallback failed', err && err.message);
          }
        }
        if (!decisionClicked) {
          console.log('Decision/button fallback failed, saving diagnostics...');
          await page.screenshot({ path: path.resolve(__dirname) + '/decision-error.jpeg', type: 'jpeg', quality: 100 });
          try { fs.writeFileSync(path.resolve(__dirname, 'decision-error.html'), await page.content(), 'utf8'); if (argv.debug) console.log('Saved decision HTML to', path.resolve(__dirname, 'decision-error.html')); } catch (e) { console.warn('Failed to write decision HTML:', e && e.message); }
          throw new Error('Decision button not found');
        }
      }

      // Now try the service terms checkbox (may be a different selector). Try multiple fallbacks.
      const termsSelectors = ['table #trial_request_voucher_form_serviceTerms', 'input[name*="serviceTerms"]', 'input[id*="serviceTerms"]', 'input[type="checkbox"]'];
      let termsClicked = false;
      for (const ts of termsSelectors) {
        try {
          const el = await page.$(ts);
          if (el) {
            await page.click(ts);
            termsClicked = true;
            if (argv.debug) console.log('Clicked terms checkbox via', ts);
            break;
          }
        } catch (e) {
          if (argv.debug) console.warn('Terms click failed for', ts, e && e.message);
        }
      }
      if (!termsClicked && argv.debug) console.warn('No terms checkbox found; continuing without explicit click');

      await page.keyboard.down("Tab");
      await page.keyboard.down("Tab");
      await page.keyboard.press("Enter");

      await page.waitForNavigation({ timeout: argv.timeout });

      var pageText = await page.evaluate(() => {
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

      if (argv.debug) {
        console.log(pageText);
      }

      if (pageText.toLowerCase().includes("you are now connected")) {
        let t = new Date().toLocaleString();

        console.log("Wifi Connected Successfully", t);

        await page.screenshot({
          path: path.resolve(__dirname) + "/result.jpeg",
          type: "jpeg",
          quality: 100,
        });
      } else {
        await page.screenshot({
          path: path.resolve(__dirname) + "/error-result.jpeg",
          type: "jpeg",
          quality: 100,
        });
      }

      await browser.close();

      setTimeout(run, 60000 * 60);
    }
  );
})();
