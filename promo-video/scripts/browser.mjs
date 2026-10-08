import {existsSync, accessSync, constants} from 'node:fs';
import {delimiter, isAbsolute, join, resolve} from 'node:path';

export function findBrowser(env = process.env) {
  const explicit = env.REMOTION_BROWSER_EXECUTABLE;
  const names = explicit ? [explicit] : ['chromium-browser', 'chromium', 'google-chrome', 'google-chrome-stable'];
  for (const name of names) {
    const paths = isAbsolute(name) ? [name] : (env.PATH ?? '').split(delimiter).map((dir) => join(dir, name));
    for (const candidate of paths) {
      try {
        accessSync(candidate, constants.X_OK);
        if (existsSync(candidate)) return resolve(candidate);
      } catch {
        // Continue through PATH.
      }
    }
  }
  if (explicit) throw new Error('REMOTION_BROWSER_EXECUTABLE 指向的浏览器不可执行。');
  return undefined;
}
