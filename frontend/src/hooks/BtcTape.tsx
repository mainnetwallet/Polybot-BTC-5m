import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "../api/client";
import { useBot } from "./BotContext";

/**
 * Candles for the BTC tape, built in the browser from the Chainlink price the backend streams in every
 * state frame (snapshot.btc). When the page opens it also asks the server for the ticks it already saw
 * (about 2 hours), so past rounds are drawn too. Nothing is invented: a candle exists only for time that
 * has real prices, and the chart shades the part it has none for.
 */

export const CANDLE_MS = 5000;
const MAX_TICKS = 7300; // about 2 hours of one-per-second ticks
const MIN_TICK_GAP_MS = 250; // two ticks closer than this are the same published price
const STORAGE_KEY = "polybot.btcTicks.v2";

export interface BtcTick {
  /** when the price was published, on the server clock (ms) */
  t: number;
  p: number;
}

export interface Candle {
  t: number;
  o: number;
  h: number;
  l: number;
  c: number;
}

const TapeContext = createContext<BtcTick[]>([]);

function isTick(row: unknown): row is BtcTick {
  return typeof row === "object" && row !== null && Number.isFinite((row as BtcTick).t) && Number.isFinite((row as BtcTick).p);
}

function readStored(): BtcTick[] {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const cutoff = Date.now() - 125 * 60_000;
    return parsed.filter(isTick).filter((row) => row.t > cutoff).slice(-MAX_TICKS);
  } catch {
    return [];
  }
}

/** Sorted by time, one tick per published price. */
function mergeTicks(a: BtcTick[], b: BtcTick[]): BtcTick[] {
  const all = [...a, ...b].sort((x, y) => x.t - y.t);
  const out: BtcTick[] = [];
  for (const tick of all) {
    const prev = out[out.length - 1];
    if (prev && tick.t - prev.t < MIN_TICK_GAP_MS) {
      out[out.length - 1] = tick;
      continue;
    }
    out.push(tick);
  }
  return out.length > MAX_TICKS ? out.slice(out.length - MAX_TICKS) : out;
}

export function BtcTapeProvider({ children }: { children: ReactNode }) {
  const { snapshot } = useBot();
  const [ticks, setTicks] = useState<BtcTick[]>(readStored);
  const lastTs = useRef(0);
  const lastSaved = useRef(0);
  const btc = snapshot?.btc;
  const serverTime = snapshot?.server_time;
  const hasSnapshot = snapshot !== null && snapshot !== undefined;

  // past ticks from the server, once the dashboard is connected
  useEffect(() => {
    if (!hasSnapshot) return;
    let cancelled = false;
    api
      .btcTicks()
      .then((res) => {
        if (cancelled || !Array.isArray(res.ticks)) return;
        const history = res.ticks.filter((row) => Array.isArray(row) && Number.isFinite(row[0]) && Number.isFinite(row[1])).map(([t, p]) => ({ t, p }));
        if (history.length > 0) setTicks((prev) => mergeTicks(prev, history));
      })
      .catch(() => {
        // older backend without the endpoint, or offline: the chart just builds from live prices
      });
    return () => {
      cancelled = true;
    };
  }, [hasSnapshot]);

  useEffect(() => {
    if (!btc || typeof btc.price !== "number" || typeof btc.age_ms !== "number") return;
    const serverMs = Date.parse(serverTime ?? "");
    if (!Number.isFinite(serverMs)) return;
    const t = Math.round(serverMs - btc.age_ms);
    if (t <= lastTs.current) return; // the same published price seen again
    lastTs.current = t;
    const price = btc.price;
    setTicks((prev) => {
      const next = mergeTicks(prev, [{ t, p: price }]);
      const now = Date.now();
      if (now - lastSaved.current > 5000) {
        lastSaved.current = now;
        try {
          window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
        } catch {
          // storage can be blocked; the tape then only lives until the next refresh
        }
      }
      return next;
    });
  }, [btc, serverTime]);

  return <TapeContext.Provider value={ticks}>{children}</TapeContext.Provider>;
}

export function useBtcTicks(): BtcTick[] {
  return useContext(TapeContext);
}

export interface TapeWindow {
  candles: Candle[];
  /** first price this browser has inside the window (null = none) */
  firstTickMs: number | null;
  ticks: number;
}

/**
 * Candles of `candleMs` that fall inside [startMs, endMs). Like on an exchange chart, a candle opens at the
 * close of the one before it, so neighbours touch instead of leaving gaps.
 */
export function useBtcCandles(startMs: number | null, endMs: number | null, candleMs: number = CANDLE_MS): TapeWindow {
  const ticks = useBtcTicks();
  return useMemo(() => {
    if (startMs === null || endMs === null) return { candles: [], firstTickMs: null, ticks: 0 };
    const candles: Candle[] = [];
    let current: Candle | null = null;
    let firstTickMs: number | null = null;
    let used = 0;
    for (const tick of ticks) {
      if (tick.t < startMs || tick.t >= endMs) continue;
      used += 1;
      if (firstTickMs === null) firstTickMs = tick.t;
      const bucket = Math.floor(tick.t / candleMs) * candleMs;
      if (!current || current.t !== bucket) {
        const prev: Candle | undefined = candles[candles.length - 1];
        const open: number = prev && bucket - prev.t <= candleMs * 2 ? prev.c : tick.p;
        const next: Candle = { t: bucket, o: open, h: Math.max(open, tick.p), l: Math.min(open, tick.p), c: tick.p };
        candles.push(next);
        current = next;
      } else {
        current.h = Math.max(current.h, tick.p);
        current.l = Math.min(current.l, tick.p);
        current.c = tick.p;
      }
    }
    return { candles, firstTickMs, ticks: used };
  }, [ticks, startMs, endMs, candleMs]);
}
