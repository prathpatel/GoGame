import { type Prop, propWithEffect } from 'lib';
import type { ColorChoice, ColorProp } from 'lib/setup/color';
import { timeModes, timeControlFromStoredValues, type TimeControl } from 'lib/setup/timeControl';
import { storedJsonProp } from 'lib/storage';
import { alert, type Dialog } from 'lib/view';
import * as xhr from 'lib/xhr';

import type LobbyController from './ctrl';
import type { ForceSetupOptions, GameMode, GameType, Pool, PoolMember, SetupStore } from './interfaces';
import setupModal from './view/setup/modal';

const getPerf = (tc: TimeControl): Perf => tc.speed();

export const boardSizes = [9, 13, 19] as const;
export const handicaps = [0, 2, 3, 4, 5, 6, 7, 8, 9] as const;

export default class SetupController {
  store: Record<GameType, Prop<SetupStore>>;
  gameType: GameType | null = null;
  friendUser = '';
  loading = false;
  color: ColorProp;
  forced?: ForceSetupOptions;

  // Store props
  size: Prop<number>;
  handicap: Prop<number>;
  gameMode: Prop<GameMode>;
  ratingMin: Prop<number>;
  ratingMax: Prop<number>;
  aiLevel: Prop<number>;

  timeControl: TimeControl;
  // time presets: the quick pairing pools of the selected board size, updated in place
  private readonly presets: Pool[] = [];

  constructor(readonly root: LobbyController) {
    this.color = propWithEffect('random', this.onPropChange);
    // Initialize stores with default props as necessary
    this.store = {
      hook: this.makeSetupStore('hook'),
      friend: this.makeSetupStore('friend'),
      ai: this.makeSetupStore('ai'),
    };
  }

  makeSetupStore = (gameType: GameType) =>
    storedJsonProp<SetupStore>(`lobby.setup.${this.root.me?.username || 'anon'}.${gameType}`, () => ({
      size: gameType === 'ai' ? 9 : 19,
      handicap: 0,
      timeMode: gameType === 'hook' ? 'realTime' : 'unlimited',
      time: 10,
      increment: 15,
      days: 2,
      gameMode: gameType === 'ai' || !this.root.me ? 'casual' : 'rated',
      color: 'random',
      ratingMin: -500,
      ratingMax: 500,
      aiLevel: 1,
    }));

  private readonly loadPropsFromStore = (forceOptions?: ForceSetupOptions) => {
    const storeProps = this.store[this.gameType!]();
    // Load props from the store, but override any store values with values found in forceOptions
    this.size = propWithEffect(forceOptions?.size || storeProps.size || 19, this.onDropdownChange);
    this.handicap = this.propWithApply(forceOptions?.handicap ?? storeProps.handicap ?? 0);
    const canChangeTimeMode = !!this.root.me || this.gameType !== 'hook';
    this.timeControl = timeControlFromStoredValues(
      propWithEffect(forceOptions?.timeMode || storeProps.timeMode, this.onDropdownChange),
      canChangeTimeMode ? timeModes : ['realTime'],
      forceOptions?.time ?? storeProps.time,
      forceOptions?.increment ?? storeProps.increment,
      forceOptions?.days ?? storeProps.days,
      this.onPropChange,
      this.presets,
    );
    this.gameMode = this.propWithApply(forceOptions?.mode ?? storeProps.gameMode);
    this.ratingMin = this.propWithApply(storeProps.ratingMin);
    this.ratingMax = this.propWithApply(storeProps.ratingMax);
    this.aiLevel = this.propWithApply(storeProps.aiLevel);
    this.color(forceOptions?.color || storeProps.color || 'random');

    this.enforcePropRules();
    // Upon loading the props from the store, overriding with forced options, and enforcing rules,
    // immediately save them to the store. This way, the user can know that whatever they saw last
    // in the modal will be there when they open it at a later time.
    this.savePropsToStore();
  };

  private readonly enforcePropRules = () => {
    // reassign with this.propWithApply in this function to avoid calling this.onPropChange

    this.presets.splice(0, this.presets.length, ...this.root.pools.filter(p => p.size === this.size()));

    // keep the handicap within what the board size allows
    const maxHandicap = this.size() === 9 ? 5 : 9;
    if (this.handicap() > maxHandicap || this.handicap() === 1 || this.gameType === 'hook')
      this.handicap = this.propWithApply(this.gameType === 'hook' ? 0 : Math.min(this.handicap(), maxHandicap));

    if (this.gameMode() === 'rated' && this.ratedModeDisabled()) {
      this.gameMode = this.propWithApply('casual');
    }

    this.ratingMin = this.propWithApply(Math.min(0, this.ratingMin()));
    this.ratingMax = this.propWithApply(Math.max(0, this.ratingMax()));
    if (this.ratingMin() === 0 && this.ratingMax() === 0) {
      this.ratingMax = this.propWithApply(50);
    }
  };

  private readonly savePropsToStore = (override: Partial<SetupStore> = {}) => {
    if (!this.gameType) return;

    this.store[this.gameType]({
      size: this.size(),
      handicap: this.handicap(),
      timeMode: this.timeControl.mode(),
      time: this.timeControl.time(),
      increment: this.timeControl.increment(),
      days: this.timeControl.days(),
      gameMode: this.gameMode(),
      color: this.color(),
      ratingMin: this.ratingMin(),
      ratingMax: this.ratingMax(),
      aiLevel: this.aiLevel(),
      ...override,
    });
  };

  private readonly savePropsToStoreExceptRating = () =>
    this.gameType &&
    this.savePropsToStore({
      ratingMin: this.store[this.gameType]().ratingMin,
      ratingMax: this.store[this.gameType]().ratingMax,
    });

  myRating = () => this.root.data.ratingMap && Math.abs(this.root.data.ratingMap[this.selectedPerf()]);
  isProvisional = () => (this.root.data.ratingMap ? this.root.data.ratingMap[this.selectedPerf()] < 0 : true);

  private readonly onPropChange = () => {
    if (this.isProvisional()) this.savePropsToStoreExceptRating();
    else this.savePropsToStore();
    this.dialog?.redraw();
  };

  private readonly onDropdownChange = () => {
    // Handle rating update here
    this.enforcePropRules();
    if (this.isProvisional()) {
      this.ratingMin(-500);
      this.ratingMax(500);
      this.savePropsToStoreExceptRating();
    } else {
      if (this.gameType) {
        this.ratingMin(this.store[this.gameType]().ratingMin);
        this.ratingMax(this.store[this.gameType]().ratingMax);
      }
      this.savePropsToStore();
    }
    this.dialog?.redraw();
  };

  private readonly propWithApply = <A>(value: A) => propWithEffect(value, this.onPropChange);

  openModal = async (
    gameType: Exclude<GameType, 'local'>,
    forceOptions?: ForceSetupOptions,
    friendUser?: string,
  ) => {
    this.root.leavePool();
    this.gameType = gameType;
    this.loading = false;
    this.friendUser = friendUser || '';
    this.forced = forceOptions;
    this.loadPropsFromStore(forceOptions);
    this.dialog = await setupModal(this.root);
  };

  dialog?: Dialog;

  ratedModeDisabled = () =>
    // anonymous games cannot be rated
    !this.root.me ||
    this.timeControl.mode() === 'unlimited' ||
    // handicap games are never rated
    this.handicap() > 0;

  selectedPerf = (): Perf => getPerf(this.timeControl);

  ratingRange = (): string => {
    const rating = this.myRating();
    return rating ? `${Math.max(100, rating + this.ratingMin())}-${rating + this.ratingMax()}` : '';
  };

  hookToPoolMember = (color: ColorChoice): PoolMember | null => {
    const valid =
      color === 'random' &&
      this.gameType === 'hook' &&
      this.gameMode() === 'rated' &&
      this.timeControl.isRealTime();
    const id = `${this.size()}-${this.timeControl.clockStr()}`;
    return valid && this.root.pools.some(p => p.id === id)
      ? {
          id,
          range: this.ratingRange(),
        }
      : null;
  };

  propsToFormData = (color: ColorChoice) =>
    xhr.form({
      size: this.size().toString(),
      handicap: this.handicap().toString(),
      timeMode: timeModes.findIndex(tm => tm === this.timeControl.mode()),
      time: this.timeControl.time().toString(),
      increment: this.timeControl.increment().toString(),
      days: this.timeControl.days().toString(),
      mode: this.gameMode() === 'casual' ? '0' : '1',
      ratingRange: this.ratingRange(),
      level: this.aiLevel().toString(),
      color,
    });

  valid = () => this.timeControl.valid(this.minimumTimeIfReal()) && this.validConstraints();

  private readonly invalid = <A>(forced: A | undefined, current: A) =>
    forced !== undefined && forced !== current;

  private readonly validConstraints = () => {
    if (this.forced) {
      if (this.invalid(this.forced.size, this.size())) return false;
      if (this.invalid(this.forced.mode, this.gameMode())) return false;
      if (this.invalid(this.forced.timeMode, this.timeControl.mode())) return false;
      if (this.invalid(this.forced.color, this.color())) return false;
      if (
        this.timeControl.mode() === 'correspondence' &&
        this.invalid(this.forced.days, this.timeControl.days())
      )
        return false;
      if (this.timeControl.mode() === 'realTime') {
        if (this.invalid(this.forced.time, this.timeControl.time())) return false;
        if (this.invalid(this.forced.increment, this.timeControl.increment())) return false;
      }
    }
    return true;
  };

  minimumTimeIfReal = () => 0;

  submit = async () => {
    const color = this.color();
    const poolMember = this.hookToPoolMember(color);
    if (poolMember) {
      this.root.enterPool(poolMember);
      this.dialog?.close();
      return;
    }

    if (this.gameType === 'hook') this.root.setTab(this.timeControl.isRealTime() ? 'real_time' : 'seeks');
    this.loading = true;
    this.dialog?.redraw();

    let urlPath = `/setup/${this.gameType}`;
    if (this.gameType === 'hook') urlPath += `/${site.sri}`;
    const urlParams = { user: this.friendUser || undefined };
    let response;
    try {
      response = await xhr.textRaw(xhr.url(urlPath, urlParams), {
        method: 'post',
        body: this.propsToFormData(color),
      });
    } catch (_) {
      this.loading = false;
      this.dialog?.redraw();
      await alert('Sorry, we encountered an error while creating your game. Please try again.');
      return;
    }

    const { ok, redirected, url } = response;

    if (!ok) {
      this.loading = false;
      this.dialog?.redraw();
      if (response.status === 429) {
        await alert('You created many games in a short time. Please wait a minute, then try again.');
        return;
      }
      // the server answers form errors as JSON, other failures as plain text
      const body = await response.text();
      let message = body || 'Invalid setup';
      try {
        const errs: Record<string, string> = JSON.parse(body);
        message = Object.keys(errs)
          .map(k => `${k}: ${errs[k]}`)
          .join('\n');
      } catch {}
      await alert(message);
      if (response.status === 403) {
        // 403 FORBIDDEN closes this modal because challenges to the recipient
        // will not be accepted.  see friend() in controllers/Setup.scala
        this.dialog?.close();
      }
    } else if (redirected) {
      location.href = url;
    } else {
      this.loading = false;
      this.dialog?.close();
    }
  };
}
