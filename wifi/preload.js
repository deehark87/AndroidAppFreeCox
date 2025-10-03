// Overwrite the `plugins` property to use a custom getter.
Object.defineProperty(navigator, 'webdriver', {
  get: () => false,
});

// Overwrite the `plugins` property to use a custom getter.
Object.defineProperty(navigator, 'plugins', {
  get: () => [1, 2, 3, 4, 5],
});

// Overwrite the `languages` property to use a custom getter.
Object.defineProperty(navigator, 'languages', {
  get: () => ['en-US', 'en'],
});

// Pass the Chrome Test.
window.chrome = {
  runtime: {},
};

// Pass the Permissions Test.
const originalQuery = window.navigator.permissions.query;
window.navigator.permissions.query = (parameters) => (
  parameters.name === 'notifications' ?
    Promise.resolve({ state: Notification.permission }) :
    originalQuery(parameters)
);
