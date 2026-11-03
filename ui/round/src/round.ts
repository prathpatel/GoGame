import { attributesModule, classModule, eventListenersModule, init } from 'snabbdom';

import standaloneChat from 'lib/chat/standalone';
import { pubsub } from 'lib/pubsub';
import { wsConnect } from 'lib/socket';
import { text as xhrText } from 'lib/xhr';

import RoundCtrl from './ctrl';
import type { RoundOpts } from './interfaces';
import { main as view } from './view';

const patch = init([classModule, attributesModule, eventListenersModule]);

export async function initModule(opts: RoundOpts): Promise<RoundCtrl> {
  const { data, chat } = opts;
  let ctrl: RoundCtrl | undefined;

  const socketUrl = data.player.spectator
    ? `/watch/${data.game.id}/${data.player.color}/v6`
    : `/play/${data.game.id}${data.player.id}/v6`;

  opts.socketSend = wsConnect(socketUrl, data.player.version ?? 0, {
    options: { reloadOnResume: true },
    receive: (t: string, d: any) => ctrl?.receive(t, d) ?? false,
    events: {
      endData() {
        // refresh the game metadata in the side panel
        xhrText(`/game/${data.game.id}/${data.player.color}/sides`).then(html => {
          const $html = $(html);
          const $meta = $html.find('.game__meta');
          if ($meta.length) $('.game__meta').replaceWith($meta);
          $('.crosstable').replaceWith($html.find('.crosstable'));
          pubsub.emit('content-loaded');
        });
      },
    },
  }).send;

  ctrl = new RoundCtrl(opts, redraw);
  const el = document.querySelector('.round__app') as HTMLElement;
  let vnode = patch(el, view(ctrl));
  function redraw() {
    vnode = patch(vnode, view(ctrl!));
  }

  if (chat) chat.instance = standaloneChat(chat);

  return ctrl;
}
