import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useBot } from "./BotContext";

/**
 * 5 second candles for the BTC tape, built in the browser from the live Chainlink price the backend already
 * streams in every state frame (snapshot.btc). The backend does not provide candles, so nothing here is
 * invented: a candle exists only for time the dashboard actually received prices, and the chart says so.
 */

export const CANDLE_MS = 5000;
const MAX_TICKS = 1500; // about 25 minutes of one-per-second ticks
const STORAGE_KEY = "polybot.btcTicks.v1";

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

function readStored(): BtcTick[] {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const cutoff = Date.now() - 30 * 60_000;
    return parsed
      .filter(
        (row): row is BtcTick =>
          typeof row === "object" && row !== null && Number.isFinite((row as BtcTick).t) && Number.isFinite((row as BtcTick).p),
      )
      .filter((row) => row.t > cutoff)
      .slice(-MAX_TICKS);
  } catch {
    return [];
  }
}

export function BtcTapeProvider({ children }: { children: ReactNode }) {
  const { snapshot } = useBot();
  const [ticks, setTicks] = useState<BtcTick[]>(readStored);
  const lastTs = useRef(0);
  const lastSaved = useRef(0);
  const btc = snapshot?.btc;
  const serverTime = snapshot?.server_time;

  useEffect(() => {
    if (!btc || typeof btc.price !== "number" || typeof btc.age_ms !== "number") return;
    const serverMs = Date.parse(serverTime ?? "");
    if (!Number.isFinite(serverMs)) return;
    const t = Math.round(serverMs - btc.age_ms);
    if (t <= lastTs.current) return; // the same published price seen again
    lastTs.current = t;
    const price = btc.price;
    setTicks((prev) => {
      const next = prev.length >= MAX_TICKS ? prev.slice(prev.length - MAX_TICKS + 1) : prev.slice();
      next.push({ t, p: price });
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
  /** first price this browser received inside the window (null = none yet) */
  firstTickMs: number | null;
  ticks: number;
}

/** The candles that fall inside [startMs, endMs). */
export function useBtcCandles(startMs: number | null, endMs: number | null): TapeWindow {
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
      const bucket = Math.floor(tick.t / CANDLE_MS) * CANDLE_MS;
      if (!current || current.t !== bucket) {
        current = { t: bucket, o: tick.p, h: tick.p, l: tick.p, c: tick.p };
        candles.push(current);
      } else {
        current.h = Math.max(current.h, tick.p);
        current.l = Math.min(current.l, tick.p);
        current.c = tick.p;
      }
    }
    return { candles, firstTickMs, ticks: used };
  }, [ticks, startMs, endMs]);
}
