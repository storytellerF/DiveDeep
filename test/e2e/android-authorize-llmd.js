const { remote } = require('webdriverio');

const LLMD_PACKAGE = 'com.storytellerf.llmd';
const LLMD_AUTH_ACTIVITY = '.LlmdIpcAuthorizationActivity';
const AUTH_ACTION = 'com.storytellerf.llmd.action.AUTHORIZE_CALLER';
const CALLER_PACKAGE = 'com.storyteller_f.divedeep';

async function findByText(driver, text) {
  const element = await driver.$(`android=new UiSelector().text("${text}")`);
  if (await element.isExisting()) {
    return element;
  }
  return null;
}

async function main() {
  const driver = await remote({
    hostname: process.env.APPIUM_HOST || '127.0.0.1',
    port: Number(process.env.APPIUM_PORT || 4723),
    path: '/',
    capabilities: {
      platformName: 'Android',
      'appium:automationName': 'UiAutomator2',
      'appium:appPackage': LLMD_PACKAGE,
      'appium:appActivity': LLMD_AUTH_ACTIVITY,
      'appium:intentAction': AUTH_ACTION,
      'appium:optionalIntentArguments': `--es caller_package ${CALLER_PACKAGE}`,
      'appium:noReset': true,
      'appium:newCommandTimeout': 120,
      ...(process.env.DEVICE ? { 'appium:udid': process.env.DEVICE } : {}),
    },
  });

  try {
    await driver.waitUntil(
      async () => Boolean(await findByText(driver, '允许')),
      {
        timeout: 30000,
        interval: 1000,
        timeoutMsg: 'llmd authorization button did not appear',
      },
    );
    const allowButton = await findByText(driver, '允许');
    await allowButton.click();
  } finally {
    await driver.deleteSession();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
