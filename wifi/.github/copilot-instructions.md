## Purpose

Concise, actionable guidance for AI coding agents working in this repository (Node.js + Puppeteer + host shell integration).

## Big picture (quick)

- `free-wifi.js`: the primary long-running worker. Flow: call `Macchangerizer.sh` → launch Puppeteer → inject `preload.js` via page.evaluateOnNewDocument → navigate to captive portal (cwifi-new.cox.com) → fill/register form → save screenshots → intentionally restart with setTimeout.
- `preload.js`: runtime/browser overrides injected into every page (webdriver, plugins, languages, chrome shim, permissions.query). This is the main anti-detection surface.
- `Macchangerizer.sh`: native helper that runs `macchanger` and stops/starts `NetworkManager`. Requires sudo and mutates the host network stack.

## Key developer workflows

- Run locally (fish):

  node free-wifi.js --iface wlan0 --debug

- Important flags (from `free-wifi.js`): `--iface/-i` (interface, required), `--debug/-d` (dump HTML & extra logs), `--timeout/-t` (navigation timeout ms).
- JS deps: add a `package.json` then: npm install puppeteer shelljs node-random-name random-useragent yargs
- System deps: `macchanger`, an active `NetworkManager` service and sudo. Do not run on CI without sandboxing or mocks.

## Project-specific patterns and conventions

- Anti-detection injection: edits to page behavior belong in `preload.js`. The main injection call is page.evaluateOnNewDocument(preloadFile).
- Puppeteer pattern: create a BrowserContext and enable request interception. Example from repo: const context = await browser.createBrowserContext(); const page = await context.newPage(); await page.setRequestInterception(true);
- Fragile selectors: the repo contains brittle selectors (example):

  #signIn > .signInText > .freeAccessPassSignup > .floatleft > .coxRegisterButton

  There is already a fallback that enumerates buttons and logs them when `--debug` is set. Preserve and extend this diagnostic behavior instead of removing it.
- Debug artifacts: the code writes screenshots used for diagnostics — preserve these filenames when changing flows: `landing.jpeg`, `registration.jpeg`, `registration-filled.jpeg`, `selector-error.jpeg`, `result.jpeg`, `error-result.jpeg`.

## Integration points & external dependencies

- External captive portal: `http://cwifi-new.cox.com/...`. Interactions are environment-dependent; tests relying on it will be flaky. Prefer recorded HTML or mocks for unit tests.
- Native tools used by `Macchangerizer.sh`: `macchanger`, `ifconfig`/`ip`, `service NetworkManager` — these require sudo and modify system state. Any change touching this script must call out the privilege/side-effect implications in PRs.

## Editing guidance for AI agents (practical rules)

- Preserve `--debug` behavior and all screenshot output. Humans rely on these artifacts for debugging UI regressions.
- Do not remove or silently change `Macchangerizer.sh`. If you modify it, add clear documentation in the PR about required privileges and expected side effects.
- Prefer non-destructive edits: add retries, increase timeouts, broaden selectors (text/XPath), and add extra screenshots instead of replacing flows.
- When selectors fail, add an XPath/text-based fallback (page.$x()) and log candidate elements' innerText in debug mode.

## Concrete code patterns & examples (from this repo)

- Inject preload at page start:

  page.evaluateOnNewDocument(preloadFile)

- Create context + intercept requests:

  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.setRequestInterception(true);

- Fallback selector approach (already present): enumerate document.querySelectorAll('button') and log innerText when waitForSelector fails; keep storing the `selector-error.jpeg` screenshot.

## Files to inspect first

- `free-wifi.js` — primary worker, CLI flags, screenshots, retry loop
- `preload.js` — anti-detection/runtime overrides
- `Macchangerizer.sh` — mac spoofing and NetworkManager calls (requires sudo)
- `test-yargs.js` — small CLI parsing example


If you'd like this condensed into a one-paragraph PR checklist (e.g., "touching native scripts? add WARNING; touching preload? list tests"), tell me which format you prefer and I'll add it.
