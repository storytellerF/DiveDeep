const {
  click,
  createSession,
  deleteSession,
  findByText,
  waitUntil,
} = require('./android-appium');

const APP_PACKAGE = 'com.storyteller_f.divedeep';
const APP_ACTIVITY = '.MainActivity';
const BUTTON_ENABLE = '开始翻译';
const BUTTON_DISABLE = '停止翻译';

function desiredEnabled() {
  const value = process.argv[2];
  if (value === 'true') return true;
  if (value === 'false') return false;
  throw new Error('Usage: node test/e2e/android-toggle.js <true|false>');
}

async function main() {
  const enabled = desiredEnabled();
  const sessionId = await createSession({
    'appium:appPackage': APP_PACKAGE,
    'appium:appActivity': APP_ACTIVITY,
    'appium:noReset': true,
    'appium:forceAppLaunch': true,
    'appium:newCommandTimeout': 120,
  });

  try {
    const targetText = enabled ? BUTTON_ENABLE : BUTTON_DISABLE;
    const oppositeText = enabled ? BUTTON_DISABLE : BUTTON_ENABLE;
    await waitUntil(
      async () => Boolean((await findByText(sessionId, targetText)) || (await findByText(sessionId, oppositeText))),
      {
        timeout: 30000,
        interval: 1000,
        timeoutMsg: `DiveDeep toggle button did not appear: ${targetText}`,
      },
    );

    const targetButton = await findByText(sessionId, targetText);
    if (targetButton) {
      await click(sessionId, targetButton);
      await waitUntil(
        async () => Boolean(await findByText(sessionId, oppositeText)),
        {
          timeout: 10000,
          interval: 500,
          timeoutMsg: `DiveDeep toggle did not switch to: ${oppositeText}`,
        },
      );
    }
  } finally {
    await deleteSession(sessionId);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
