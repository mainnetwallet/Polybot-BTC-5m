import { useSyncExternalStore } from "react";

// Dashboard preferences that live in this browser only (like the Android app's own preferences).
// They never touch the bot's runtime settings on the server.

const CONFIRM_SELLS_KEY = "polybot.confirmSells";
const listeners = new Set<() => void>();
let confirmSells: boolean | null = null;

function readConfirmSells(): boolean {
  try {
    return window.localStorage.getItem(CONFIRM_SELLS_KEY) === "true"; // default: off
  } catch {
    return false;
  }
}

function currentConfirmSells(): boolean {
  if (confirmSells === null) confirmSells = readConfirmSells();
  return confirmSells;
}

/** Turns the "ask before a manual sell" dialog on or off (all open tabs follow). */
export function setConfirmSells(enabled: boolean): void {
  confirmSells = enabled;
  try {
    window.localStorage.setItem(CONFIRM_SELLS_KEY, String(enabled));
  } catch {
    // storage can be blocked; the choice then only lasts until the next refresh
  }
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== CONFIRM_SELLS_KEY) return;
    confirmSells = readConfirmSells();
    listener();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

/** `[ask before manual sells, setter]`. Default off: a manual sell goes out the moment it is clicked. */
export function useConfirmSells(): [boolean, (enabled: boolean) => void] {
  const value = useSyncExternalStore(subscribe, currentConfirmSells, () => false);
  return [value, setConfirmSells];
}

// ---- One-tap buy amounts (the three dollar buttons of the one-tap panel) ----

const TAP_AMOUNTS_KEY = "polybot.tapAmounts";
export const DEFAULT_TAP_AMOUNTS: readonly [number, number, number] = [1, 2, 5];
const tapListeners = new Set<() => void>();
let tapAmountsRaw: string | null = null;

function readTapAmountsRaw(): string {
  try {
    return window.localStorage.getItem(TAP_AMOUNTS_KEY) ?? "";
  } catch {
    return "";
  }
}

function currentTapAmountsRaw(): string {
  if (tapAmountsRaw === null) tapAmountsRaw = readTapAmountsRaw();
  return tapAmountsRaw;
}

function parseTapAmounts(raw: string): number[] {
  const parsed = raw
    .split(",")
    .map((part) => Number(part))
    .filter((value) => Number.isFinite(value) && value >= 1 && value <= 100000);
  return parsed.length === 3 ? parsed : [...DEFAULT_TAP_AMOUNTS];
}

/** Saves the three one-tap amounts (whole or decimal dollars, each at least $1). Kept in this browser only. */
export function setTapAmounts(amounts: number[]): void {
  const clean = amounts.slice(0, 3).map((value) => Math.round(value * 100) / 100);
  if (clean.length !== 3 || clean.some((value) => !(value >= 1))) return;
  tapAmountsRaw = clean.join(",");
  try {
    window.localStorage.setItem(TAP_AMOUNTS_KEY, tapAmountsRaw);
  } catch {
    // storage can be blocked; the choice then only lasts until the next refresh
  }
  tapListeners.forEach((listener) => listener());
}

function subscribeTap(listener: () => void): () => void {
  tapListeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== TAP_AMOUNTS_KEY) return;
    tapAmountsRaw = readTapAmountsRaw();
    listener();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    tapListeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

/** `[the three one-tap amounts, setter]`. Default $1, $2, $5. */
export function useTapAmounts(): [number[], (amounts: number[]) => void] {
  const raw = useSyncExternalStore(subscribeTap, currentTapAmountsRaw, () => "");
  // the raw string is the stable snapshot; the array is derived from it
  return [parseTapAmounts(raw), setTapAmounts];
}

// ---- One-tap buy: ON/OFF switch and fold state (this browser only) ----

/** A boolean preference kept in localStorage; all open tabs follow. */
function createBoolPref(key: string, fallback: boolean): () => [boolean, (value: boolean) => void] {
  const listeners = new Set<() => void>();
  let cached: boolean | null = null;

  const read = (): boolean => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw === null ? fallback : raw === "true";
    } catch {
      return fallback;
    }
  };
  const current = (): boolean => {
    if (cached === null) cached = read();
    return cached;
  };
  const set = (value: boolean): void => {
    cached = value;
    try {
      window.localStorage.setItem(key, String(value));
    } catch {
      // storage can be blocked; the choice then only lasts until the next refresh
    }
    listeners.forEach((listener) => listener());
  };
  const subscribe = (listener: () => void): (() => void) => {
    listeners.add(listener);
    const onStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== key) return;
      cached = read();
      listener();
    };
    window.addEventListener("storage", onStorage);
    return () => {
      listeners.delete(listener);
      window.removeEventListener("storage", onStorage);
    };
  };

  return () => [useSyncExternalStore(subscribe, current, () => fallback), set];
}

/** `[one-tap buy is on, setter]`. Default OFF: the tap buttons stay locked until it is switched on, so a stray click cannot buy. */
export const useTapEnabled = createBoolPref("polybot.tapEnabled", false);

// ---- One-tap buy: the minimum share price (this browser only) ----

const TAP_MIN_ASK_KEY = "polybot.tapMinAsk";
const minAskListeners = new Set<() => void>();
let minAskRaw: string | null = null;

function readMinAskRaw(): string {
  try {
    return window.localStorage.getItem(TAP_MIN_ASK_KEY) ?? "";
  } catch {
    return "";
  }
}

function currentMinAskRaw(): string {
  if (minAskRaw === null) minAskRaw = readMinAskRaw();
  return minAskRaw;
}

/** "0.4" -> 0.4; empty / invalid -> null (nothing saved: the server's default applies). 0 is a real value: no minimum. */
function parseMinAsk(raw: string): number | null {
  if (raw.trim() === "") return null;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 && value <= 0.99 ? value : null;
}

/** Saves the lowest share price (0 to 0.99, 0 = none) a one-tap buy is allowed at. Kept in this browser only. */
export function setTapMinAsk(price: number): void {
  if (!Number.isFinite(price) || price < 0 || price > 0.99) return;
  minAskRaw = String(Math.round(price * 1000) / 1000);
  try {
    window.localStorage.setItem(TAP_MIN_ASK_KEY, minAskRaw);
  } catch {
    // storage can be blocked; the choice then only lasts until the next refresh
  }
  minAskListeners.forEach((listener) => listener());
}

function subscribeMinAsk(listener: () => void): () => void {
  minAskListeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== TAP_MIN_ASK_KEY) return;
    minAskRaw = readMinAskRaw();
    listener();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    minAskListeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

/** `[saved minimum share price as 0-0.99 or null when none was saved, setter]`. */
export function useTapMinAsk(): [number | null, (price: number) => void] {
  const raw = useSyncExternalStore(subscribeMinAsk, currentMinAskRaw, () => "");
  return [parseMinAsk(raw), setTapMinAsk];
}
