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
