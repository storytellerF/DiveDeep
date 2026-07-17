const ELEMENT_KEY = 'element-6066-11e4-a52e-4f735466cecf';

function appiumBaseUrl() {
  const host = process.env.APPIUM_HOST || '127.0.0.1';
  const port = Number(process.env.APPIUM_PORT || 4723);
  return `http://${host}:${port}`;
}

async function request(sessionId, method, path, body) {
  const response = await fetch(`${appiumBaseUrl()}${sessionId ? `/session/${sessionId}` : ''}${path}`, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = payload.value?.message || payload.message || response.statusText;
    const error = new Error(`${method} ${path} failed: ${message}`);
    error.status = response.status;
    throw error;
  }
  return payload.value;
}

async function createSession(capabilities) {
  const value = await request(null, 'POST', '/session', {
    capabilities: {
      alwaysMatch: {
        platformName: 'Android',
        'appium:automationName': 'UiAutomator2',
        ...capabilities,
        ...(process.env.DEVICE ? { 'appium:udid': process.env.DEVICE } : {}),
      },
      firstMatch: [{}],
    },
  });
  return value.sessionId;
}

async function deleteSession(sessionId) {
  await request(sessionId, 'DELETE', '', null);
}

async function findByText(sessionId, text) {
  try {
    const element = await request(sessionId, 'POST', '/element', {
      using: '-android uiautomator',
      value: `new UiSelector().text("${text}")`,
    });
    return element[ELEMENT_KEY] || element.ELEMENT;
  } catch (error) {
    if (error.status === 404) {
      return null;
    }
    throw error;
  }
}

async function click(sessionId, elementId) {
  await request(sessionId, 'POST', `/element/${elementId}/click`, {});
}

async function waitUntil(predicate, { timeout, interval, timeoutMsg }) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
  throw new Error(timeoutMsg);
}

module.exports = {
  click,
  createSession,
  deleteSession,
  findByText,
  waitUntil,
};
