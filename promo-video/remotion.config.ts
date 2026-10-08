import {Config} from '@remotion/cli/config';
// @ts-expect-error Small Node helper is shared with the headless renderer.
import {findBrowser} from './scripts/browser.mjs';

Config.setPublicDir('.local/public');
Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
const browser = findBrowser();
if (browser) Config.setBrowserExecutable(browser);
