import { promiseTimeout } from 'lib/async';
import { wsDestroy } from 'lib/socket';

let redirectInProgress: false | string = false;

export const redirect = async (opts: RedirectTo, beep?: boolean) => {
  try {
    if (beep) await promiseTimeout(site.sound.play('genericNotify'), 1000);
  } catch (e) {
    console.warn(e);
  }
  let url: string;
  if (typeof opts === 'string') url = opts;
  else {
    url = `/game/${opts.id}`;
    if (opts.cookie) {
      // browsers refuse a domain attribute on localhost and on IP addresses: leave it out there
      const host = location.hostname;
      const named = host.includes('.') && !/^[\d.]+$/.test(host) && !host.includes(':');
      const domain = named ? '; domain=' + host : '';
      document.cookie = [
        encodeURIComponent(opts.cookie.name) + '=' + opts.cookie.value,
        '; max-age=' + opts.cookie.maxAge,
        '; path=/',
        domain,
      ].join('');
    }
  }
  const href = '//' + location.host + '/' + url.replace(/^\//, '');
  redirectInProgress = href;
  location.href = href;
};

export const unload = {
  expected: false,
};

export const reload = (err?: any) => {
  if (err) console.warn(err);
  if (redirectInProgress) return;
  unload.expected = true;
  wsDestroy();
  if (location.hash) location.reload();
  else location.assign(location.href);
};
