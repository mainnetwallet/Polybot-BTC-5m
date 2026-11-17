export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

export function isNum(value: number | null | undefined): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

export function fmtUsd(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  return `${value < 0 ? "-" : ""}$${Math.abs(value).toFixed(digits)}`;
}

export function fmtUsdGrouped(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  return `$${value.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

export function fmtSignedUsd(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  const sign = value > 0 ? "+" : value < 0 ? "-" : "";
  return `${sign}$${Math.abs(value).toFixed(digits)}`;
}

export function fmtCents(value: number | null | undefined, digits = 1): string {
  if (!isNum(value)) return "—";
  return `${(value * 100).toFixed(digits)}¢`;
}

export function fmtNum(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  return value.toFixed(digits);
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString([], {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function ago(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const ms = typeof value === "number" ? value : Date.parse(value);
  if (Number.isNaN(ms)) return "—";
  const delta = Math.max(0, Date.now() - ms);
  if (delta < 1000) return "now";
  if (delta < 60_000) return `${Math.floor(delta / 1000)}s ago`;
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  return `${Math.floor(delta / 3_600_000)}h ago`;
}

export function shortId(value: string | null | undefined, head = 8, tail = 4): string {
  if (!value) return "—";
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function pnlClass(value: number | null | undefined): string {
  if (!isNum(value) || value === 0) return "text-slate-300";
  return value > 0 ? "text-up" : "text-down";
}

/** "stage3_spread_too_wide" / "FILLED" -> "Stage3 spread too wide" / "Filled": sentence case for backend codes. */
export function sentence(value: string | null | undefined): string {
  if (!value) return "";
  const text = value.replace(/_/g, " ").trim().toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Share price in dollars with two decimals (0.93), the way the order book shows it. */
export function fmtPx(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  return value.toFixed(digits);
}

/** Seconds as m:ss (38 -> 0:38, 125 -> 2:05). */
export function fmtClock(seconds: number | null | undefined): string {
  if (!isNum(seconds)) return "—";
  const total = Math.max(0, Math.ceil(seconds));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

export interface UsdParts {
  sign: string;
  whole: string;
  dec: string;
}

/** A dollar amount split for hero numbers: $1,284 with a small .60. */
export function usdParts(value: number | null | undefined, digits = 2): UsdParts | null {
  if (!isNum(value)) return null;
  const abs = Math.abs(value);
  const [whole, dec = ""] = abs.toFixed(digits).split(".");
  return {
    sign: value < 0 ? "-" : "",
    whole: Number(whole).toLocaleString("en-US"),
    dec,
  };
}

export function fmtSignedPct(value: number | null | undefined, digits = 2): string {
  if (!isNum(value)) return "—";
  const sign = value > 0 ? "+" : value < 0 ? "-" : "";
  return `${sign}${Math.abs(value).toFixed(digits)}%`;
}

/** Age in milliseconds as "42 ms" or "1.4 s". */
export function fmtAge(ms: number | null | undefined): string {
  if (!isNum(ms)) return "—";
  const safe = Math.max(0, ms);
  return safe < 1000 ? `${Math.round(safe)} ms` : `${(safe / 1000).toFixed(1)} s`;
}

/** Whole dollars from $1 up, cents below $1 (the BTC gap to the price to beat). */
export function fmtGapUsd(value: number | null | undefined): string {
  if (!isNum(value)) return "—";
  const abs = Math.abs(value);
  return abs >= 100 ? `$${abs.toFixed(0)}` : `$${abs.toFixed(2)}`;
}
