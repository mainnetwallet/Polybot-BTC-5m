import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api, apiToken } from "../api/client";
import { TAP_STAGE } from "../lib/stages";
import type { BookSnapshot, LogRow, Mode, SellableHolding, Side, Snapshot } from "../api/types";

export type ActionResult = { ok: boolean; error?: string };
export type ToastKind = "success" | "error" | "info" | "warning";
export type FeedState = "connecting" | "open" | "offline";

export interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message?: string;
}

interface PriceHistory {
  up: number[];
  down: number[];
}

interface TickData {
  server_time_ms: number;
  remaining_seconds: number | null;
  books: { up: BookSnapshot | null; down: BookSnapshot | null };
  strategy: Partial<Snapshot["strategy"]> | null;
}

interface BotContextValue {
  snapshot: Snapshot | null;
  /** server clock minus browser clock, so countdowns use the real expiry time */
  clockOffsetMs: number;
  /** bumps whenever orders/positions/logs change so pages can refetch immediately */
  dataVersion: number;
  feedState: FeedState;
  logs: LogRow[];
  history: PriceHistory;
  toasts: Toast[];
  refresh: () => Promise<void>;
  start: (phrase?: string) => Promise<ActionResult>;
  stop: () => Promise<ActionResult>;
  switchMode: (mode: Mode, phrase?: string) => Promise<ActionResult>;
  killSwitch: (active: boolean) => Promise<ActionResult>;
  setBuys: (enabled: boolean) => Promise<ActionResult>;
  sellNow: (holding: SellableHolding) => Promise<ActionResult>;
  /** One-tap buy of `amount` USDC on `side` in the live market. A second tap within one second is ignored. */
  /** `minAsk` = lowest share price (0-0.99) the buy may be made at; the server refuses a cheaper side */
  tapBuy: (side: Side, amount: number, minAsk?: number) => Promise<ActionResult>;
  /** "stages" = Sell all (strategy stages only), "tap" = Sell all tap (one-tap shares only) */
  sellAll: (marketId: string, scope?: "stages" | "tap") => Promise<ActionResult>;
  resetPaper: (balance: number) => Promise<ActionResult>;
  resetDaily: () => Promise<ActionResult>;
  clearOrderHistory: () => Promise<ActionResult>;
  resetServer: (phrase: string) => Promise<ActionResult>;
  notify: (kind: ToastKind, title: string, message?: string) => void;
  dismissToast: (id: number) => void;
  clearLogs: () => void;
}

const BotContext = createContext<BotContextValue | null>(null);

function describeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  return "Unexpected error";
}

const HISTORY_LIMIT = 180;
/** one-tap buys: a second tap inside this window is ignored (the server keeps the same rule) */
const TAP_LOCK_MS = 1000;
const LOG_LIMIT = 400;

// "Clear view" hides every log up to the newest one seen. The logs stay in the database (audit
// trail); this id is remembered in the browser so a refresh does not bring them back.
const LOGS_CLEARED_KEY = "polybot.logs.clearedBeforeId";

function readLogsCutoff(): number {
  try {
    const value = Number(window.localStorage.getItem(LOGS_CLEARED_KEY));
    return Number.isFinite(value) && value > 0 ? value : 0;
  } catch {
    return 0;
  }
}

function writeLogsCutoff(id: number | null): void {
  try {
    if (id === null) window.localStorage.removeItem(LOGS_CLEARED_KEY);
    else window.localStorage.setItem(LOGS_CLEARED_KEY, String(id));
  } catch {
    // storage can be blocked; the clear then only lasts until the next refresh
  }
}

function pushPoint(points: number[], value: number | null | undefined): number[] {
  if (typeof value !== "number" || !Number.isFinite(value)) return points;
  const next = [...points, value];
  return next.length > HISTORY_LIMIT ? next.slice(next.length - HISTORY_LIMIT) : next;
}

export function BotProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [feedState, setFeedState] = useState<FeedState>("connecting");
  const [logs, setLogs] = useState<LogRow[]>([]);
  const [history, setHistory] = useState<PriceHistory>({ up: [], down: [] });
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [clockOffsetMs, setClockOffsetMs] = useState(0);
  const [dataVersion, setDataVersion] = useState(0);

  const historyMarket = useRef<string | null>(null);
  const lastBookStamp = useRef<{ up: number; down: number }>({ up: 0, down: 0 });
  const dataSignature = useRef("");
  const snapshotRef = useRef<Snapshot | null>(null);
  const toastSeq = useRef(0);
  const toastTimers = useRef<Set<number>>(new Set());

  // Only real book updates are charted; the chart restarts for every new market.
  const recordBooks = useCallback((next: Snapshot) => {
    const marketId = next.market?.market_id ?? null;
    let reset = false;
    if (marketId !== historyMarket.current) {
      historyMarket.current = marketId;
      lastBookStamp.current = { up: 0, down: 0 };
      reset = true;
    }
    let upPoint: number | null = null;
    let downPoint: number | null = null;
    const up = next.books.up;
    const down = next.books.down;
    if (up && up.updated_at_ms !== lastBookStamp.current.up && typeof up.best_ask === "number") {
      lastBookStamp.current.up = up.updated_at_ms;
      upPoint = up.best_ask;
    }
    if (down && down.updated_at_ms !== lastBookStamp.current.down && typeof down.best_ask === "number") {
      lastBookStamp.current.down = down.updated_at_ms;
      downPoint = down.best_ask;
    }
    if (!reset && upPoint === null && downPoint === null) return;
    setHistory((prev) => {
      const base = reset ? { up: [], down: [] } : prev;
      return { up: pushPoint(base.up, upPoint), down: pushPoint(base.down, downPoint) };
    });
  }, []);

  const applySnapshot = useCallback(
    (next: Snapshot) => {
      snapshotRef.current = next;
      setSnapshot(next);
      const serverMs = Date.parse(next.server_time);
      if (Number.isFinite(serverMs)) setClockOffsetMs(serverMs - Date.now());
      recordBooks(next);
      const signature = JSON.stringify([
        next.bot.status,
        next.bot.mode,
        next.account.trades_today,
        next.positions.map((position) => [
          position.id,
          position.size,
          position.avg_price,
          position.cost_basis,
          position.fees,
          position.realized_pnl,
          position.status,
          position.resolution,
        ]),
        next.open_orders.map((order) => [
          order.id,
          order.status,
          order.filled_size,
          order.avg_fill_price,
          order.fee,
        ]),
        next.last_order,
      ]);
      if (signature !== dataSignature.current) {
        dataSignature.current = signature;
        setDataVersion((v) => v + 1);
      }
    },
    [recordBooks],
  );

  // Lightweight push sent on every order-book event: prices, arm state, countdown.
  const applyTick = useCallback(
    (tick: TickData) => {
      setClockOffsetMs(tick.server_time_ms - Date.now());
      const prev = snapshotRef.current;
      if (!prev) return;
      const next: Snapshot = {
        ...prev,
        books: tick.books,
        strategy: tick.strategy ? { ...prev.strategy, ...tick.strategy } : prev.strategy,
        market:
          prev.market && tick.remaining_seconds !== null
            ? { ...prev.market, remaining_seconds: tick.remaining_seconds }
            : prev.market,
      };
      snapshotRef.current = next;
      setSnapshot(next);
      recordBooks(next);
    },
    [recordBooks],
  );

  const appendLog = useCallback((entry: LogRow) => {
    setLogs((prev) => {
      const next = [entry, ...prev];
      return next.length > LOG_LIMIT ? next.slice(0, LOG_LIMIT) : next;
    });
    setDataVersion((v) => v + 1);
  }, []);

  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);

  const notify = useCallback(
    (kind: ToastKind, title: string, message?: string) => {
      const id = ++toastSeq.current;
      setToasts((prev) => [...prev.slice(-3), { id, kind, title, message }]);
      const timer = window.setTimeout(() => {
        toastTimers.current.delete(timer);
        dismissToast(id);
      }, kind === "error" ? 8000 : 5000);
      toastTimers.current.add(timer);
    },
    [dismissToast],
  );

  useEffect(() => {
    const timers = toastTimers.current;
    return () => {
      timers.forEach((timer) => window.clearTimeout(timer));
      timers.clear();
    };
  }, []);

  useEffect(() => {
    let alive = true;
    api
      .state()
      .then((next) => {
        if (alive) applySnapshot(next);
      })
      .catch(() => undefined);
    api
      .logs(200)
      .then((rows) => {
        if (!alive) return;
        const newest = rows.reduce((max, row) => Math.max(max, row.id ?? 0), 0);
        let cutoff = readLogsCutoff();
        if (cutoff > newest) {
          // database was reset/wiped: ids restarted, the old cutoff no longer applies
          writeLogsCutoff(null);
          cutoff = 0;
        }
        setLogs(cutoff > 0 ? rows.filter((row) => (row.id ?? 0) > cutoff) : rows);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [applySnapshot]);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let disposed = false;
    let retry: number | undefined;
    let attempt = 0;

    const connect = () => {
      if (disposed) return;
      setFeedState("connecting");
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      const token = apiToken();
      const query = token ? `?token=${encodeURIComponent(token)}` : "";
      socket = new WebSocket(`${protocol}://${window.location.host}/api/ws${query}`);

      socket.onopen = () => {
        attempt = 0;
        if (!disposed) setFeedState("open");
      };
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as { type?: string; data?: unknown };
          if (message.type === "state" && message.data) {
            applySnapshot(message.data as Snapshot);
          } else if (message.type === "tick" && message.data) {
            applyTick(message.data as TickData);
          } else if (message.type === "log" && message.data) {
            const row = message.data as Omit<LogRow, "created_at">;
            appendLog({ ...row, created_at: new Date().toISOString() });
          }
        } catch {
          /* ignore malformed frames */
        }
      };
      socket.onerror = () => {
        socket?.close();
      };
      socket.onclose = () => {
        if (disposed) return;
        setFeedState("offline");
        // first retry is immediate; later ones back off briefly so a down server is not hammered
        const wait = attempt === 0 ? 0 : Math.min(250 * 2 ** (attempt - 1), 2000);
        attempt += 1;
        retry = window.setTimeout(connect, wait);
      };
    };

    connect();
    return () => {
      disposed = true;
      if (retry !== undefined) window.clearTimeout(retry);
      socket?.close();
    };
  }, [appendLog, applySnapshot, applyTick]);

  const refresh = useCallback(async () => {
    try {
      applySnapshot(await api.state());
    } catch {
      /* the feed indicator already surfaces connectivity */
    }
  }, [applySnapshot]);

  const start = useCallback(
    async (phrase = ""): Promise<ActionResult> => {
      const live = snapshotRef.current?.bot.mode === "live";
      try {
        applySnapshot(await api.start(live, phrase));
        notify(
          "success",
          live ? "LIVE engine started" : "Paper engine started",
          live ? "Real orders can be submitted from this session." : "Simulating fills with live Polymarket data.",
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Could not start the engine", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  const stop = useCallback(async (): Promise<ActionResult> => {
    try {
      applySnapshot(await api.stop());
      notify("info", "Engine stopped", "No new orders will be submitted.");
      return { ok: true };
    } catch (error) {
      const message = describeError(error);
      notify("error", "Could not stop the engine", message);
      return { ok: false, error: message };
    }
  }, [applySnapshot, notify]);

  const switchMode = useCallback(
    async (mode: Mode, phrase = ""): Promise<ActionResult> => {
      try {
        applySnapshot(await api.switchMode(mode, mode === "live", phrase));
        notify(
          mode === "live" ? "warning" : "info",
          mode === "live" ? "Execution mode: LIVE" : "Execution mode: PAPER",
          mode === "live"
            ? "The live engine will send real orders when started."
            : "Back to simulated execution on live data.",
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Mode switch rejected", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  const killSwitch = useCallback(
    async (active: boolean): Promise<ActionResult> => {
      try {
        applySnapshot(await api.killSwitch(active));
        notify(
          active ? "warning" : "info",
          active ? "Kill switch engaged" : "Kill switch cleared",
          active ? "Bot stopped. Clear the switch before restarting." : "The bot can be started again.",
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Kill switch request failed", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  const setBuys = useCallback(
    async (enabled: boolean): Promise<ActionResult> => {
      try {
        applySnapshot(await api.setBuys(enabled));
        notify(
          enabled ? "info" : "warning",
          enabled ? "Buying turned on" : "Buying turned off",
          enabled
            ? "New buys are allowed again."
            : "No new buys in any stage. Take-profit, stop-loss and settlement keep running.",
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Buy switch request failed", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  // Instant manual sell: no confirmation step, the request goes out the moment it is called.
  const sellNow = useCallback(
    async (holding: SellableHolding): Promise<ActionResult> => {
      try {
        const next = await api.sellNow(holding.market_id, holding.side, holding.stage);
        applySnapshot(next);
        notify(
          "success",
          "Sell sent",
          holding.stage === TAP_STAGE
            ? `One-tap ${holding.side} shares sold at the market.`
            : `Stage ${holding.stage} ${holding.side} shares sold at the market.`,
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Sell failed", message);
        void refresh();
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify, refresh],
  );

  // One-tap buy. One click = one buy: a second tap within a second, or while one is on its way, is ignored
  // here as well as on the server. Every tap carries its own id, so a request the network repeats buys once.
  const tapGate = useRef({ busy: false, last: 0 });
  const tapBuy = useCallback(
    async (side: Side, amount: number, minAsk?: number): Promise<ActionResult> => {
      const gate = tapGate.current;
      const now = Date.now();
      if (gate.busy || now - gate.last < TAP_LOCK_MS) return { ok: false, error: "ignored" };
      const marketId = snapshotRef.current?.market?.market_id;
      if (!marketId) {
        notify("error", "One-tap buy failed", "There is no live market right now.");
        return { ok: false, error: "no market" };
      }
      gate.busy = true;
      gate.last = now;
      try {
        const requestId =
          typeof crypto !== "undefined" && "randomUUID" in crypto
            ? crypto.randomUUID()
            : `${now.toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
        const next = await api.tapBuy(marketId, side, amount, requestId, minAsk);
        applySnapshot(next);
        const fill = next.tap_buy;
        notify(
          "success",
          `One-tap ${side} bought`,
          fill && fill.filled_size > 0
            ? `${fill.filled_size.toFixed(1)} shares${fill.avg_price ? ` at ${(fill.avg_price * 100).toFixed(1)}¢` : ""} for $${amount.toFixed(2)}.`
            : `$${amount.toFixed(2)} order sent.`,
        );
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "One-tap buy failed", message);
        void refresh();
        return { ok: false, error: message };
      } finally {
        gate.busy = false;
        gate.last = Date.now();
      }
    },
    [applySnapshot, notify, refresh],
  );

  // Instant "sell all": every stage that holds shares in this market is sold at the market, no confirmation step.
  const sellAll = useCallback(
    async (marketId: string, scope: "stages" | "tap" = "stages"): Promise<ActionResult> => {
      const what = scope === "tap" ? "one-tap position" : "stage";
      try {
        const next = await api.sellAll(marketId, scope);
        applySnapshot(next);
        const failed = next.manual_sell_all?.failed ?? [];
        const sold = next.manual_sell_all?.sold ?? [];
        if (failed.length > 0) {
          notify(
            "error",
            `Sold ${sold.length}, ${failed.length} not sold`,
            failed.map((f) => `${f.side}: ${f.message}`).join(" · "),
          );
        } else {
          notify(
            "success",
            scope === "tap" ? "Sell all tap sent" : "Sell all sent",
            `${sold.length} ${what}${sold.length === 1 ? "" : "s"} sold at the market.`,
          );
        }
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", scope === "tap" ? "Sell all tap failed" : "Sell all failed", message);
        void refresh();
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify, refresh],
  );

  const resetPaper = useCallback(
    async (balance: number): Promise<ActionResult> => {
      try {
        applySnapshot(await api.resetPaper(balance));
        notify("success", "Paper account reset", `Starting balance set to $${balance.toFixed(2)}.`);
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Reset failed", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  const clearOrderHistory = useCallback(async (): Promise<ActionResult> => {
    try {
      applySnapshot(await api.clearOrders());
      setDataVersion((v) => v + 1);
      notify("success", "Order history cleared", "The list is empty now. Daily PnL and stats are unchanged.");
      return { ok: true };
    } catch (error) {
      const message = describeError(error);
      notify("error", "Clear failed", message);
      return { ok: false, error: message };
    }
  }, [applySnapshot, notify]);

  const resetDaily = useCallback(async (): Promise<ActionResult> => {
    try {
      applySnapshot(await api.resetDaily());
      notify("success", "Daily stats reset", "Today's trade count and profit/loss start again from zero.");
      return { ok: true };
    } catch (error) {
      const message = describeError(error);
      notify("error", "Daily reset failed", message);
      return { ok: false, error: message };
    }
  }, [applySnapshot, notify]);

  const resetServer = useCallback(
    async (phrase: string): Promise<ActionResult> => {
      try {
        applySnapshot(await api.resetServer(phrase));
        setLogs([]);
        writeLogsCutoff(null);
        notify("success", "Server reset", "All data was wiped and settings are back to .env defaults.");
        return { ok: true };
      } catch (error) {
        const message = describeError(error);
        notify("error", "Server reset failed", message);
        return { ok: false, error: message };
      }
    },
    [applySnapshot, notify],
  );

  const clearLogs = useCallback(() => {
    setLogs((prev) => {
      const newest = prev.reduce((max, row) => Math.max(max, row.id ?? 0), 0);
      if (newest > 0) writeLogsCutoff(Math.max(newest, readLogsCutoff()));
      return [];
    });
  }, []);

  const value = useMemo<BotContextValue>(
    () => ({
      snapshot,
      clockOffsetMs,
      dataVersion,
      feedState,
      logs,
      history,
      toasts,
      refresh,
      start,
      stop,
      switchMode,
      killSwitch,
      setBuys,
      sellNow,
      tapBuy,
      sellAll,
      resetPaper,
      resetDaily,
      clearOrderHistory,
      resetServer,
      notify,
      dismissToast,
      clearLogs,
    }),
    [
      snapshot,
      clockOffsetMs,
      dataVersion,
      feedState,
      logs,
      history,
      toasts,
      refresh,
      start,
      stop,
      switchMode,
      killSwitch,
      setBuys,
      sellNow,
      tapBuy,
      sellAll,
      resetPaper,
      resetDaily,
      clearOrderHistory,
      resetServer,
      notify,
      dismissToast,
      clearLogs,
    ],
  );

  return <BotContext.Provider value={value}>{children}</BotContext.Provider>;
}

export function useBot(): BotContextValue {
  const context = useContext(BotContext);
  if (!context) throw new Error("useBot must be used inside BotProvider");
  return context;
}
