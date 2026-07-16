const { remote } = require('webdriverio');

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

async function findByText(driver, text) {
  const element = await driver.$(`android=new UiSelector().text("${text}")`);
  if (await element.isExisting()) {
    return element;
  }
  return null;
}

async function main() {
  const enabled = desiredEnabled();
  const driver = await remote({
    hostname: process.env.APPIUM_HOST || '127.0.0.1',
    port: Number(process.env.APPIUM_PORT || 4723),
    path: '/',
    capabilities: {
      platformName: 'Android',
      'appium:automationName': 'UiAutomator2',
      'appium:appPackage': APP_PACKAGE,
      'appium:appActivity': APP_ACTIVITY,
      'appium:noReset': true,
      'appium:newCommandTimeout': 120,
      ...(process.env.DEVICE ? { 'appium:udid': process.env.DEVICE } : {}),
    },
  });

  try {
    const targetText = enabled ? BUTTON_ENABLE : BUTTON_DISABLE;
    const oppositeText = enabled ? BUTTON_DISABLE : BUTTON_ENABLE;
    await driver.waitUntil(
      async () => Boolean((await findByText(driver, targetText)) || (await findByText(driver, oppositeText))),
      {
        timeout: 30000,
        interval: 1000,
        timeoutMsg: `DiveDeep toggle button did not appear: ${targetText}`,
      },
    );

    const targetButton = await findByText(driver, targetText);
    if (targetButton) {
      await targetButton.click();
      await driver.waitUntil(
        async () => Boolean(await findByText(driver, oppositeText)),
        {
          timeout: 10000,
          interval: 500,
          timeoutMsg: `DiveDeep toggle did not switch to: ${oppositeText}`,
        },
      );
    }
  } finally {
    await driver.deleteSession();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
