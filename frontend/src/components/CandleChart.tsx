import { useEffect, useRef, useState } from "react";
import { CANDLE_MS, type Candle } from "../hooks/BtcTape";
import { fmtGapUsd, isNum } from "../lib/format";
import { STAGE_VAR, type StageId } from "../lib/stages";

export interface BuyMarker {
  key: string;
  t: number;
  stage: StageId;
  label: string;
}

interface CandleChartProps {
  candles: Candle[];
  startMs: number | null;
  endMs: number | null;
  priceToBeat: number | null;
  /** latest BTC price, drawn as the tag on the right axis */
  price: number | null;
  buys: BuyMarker[];
  height?: number;
  /** fill the parent box (which must be `relative` with a height) instead of using a fixed height */
  fill?: boolean;
}

const AXIS_W = 66;
const AXIS_H = 26;
const PAD_T = 10;

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 640, height: 0 });
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      const width = Math.round(rect?.width ?? 0);
      const height = Math.round(rect?.height ?? 0);
      if (width > 0) setSize((prev) => (prev.width === width && prev.height === height ? prev : { width, height }));
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return { ref, width: size.width, measuredHeight: size.height };
}

function niceStep(range: number, target: number): number {
  const raw = range / target;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const unit = raw / pow;
  const mult = unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 2.5 ? 2.5 : unit <= 5 ? 5 : 10;
  return mult * pow;
}

function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
}

function axisPrice(value: number): string {
  return Math.round(value).toLocaleString("en-US");
}

/** 5 second candles over the whole round, with the dashed price to beat line and a marker for every buy. */
export function CandleChart({ candles, startMs, endMs, priceToBeat, price, buys, height: fixedHeight = 330, fill = false }: CandleChartProps) {
  const { ref, width, measuredHeight } = useWidth();
  const height = fill ? Math.max(150, measuredHeight) : fixedHeight;
  const plotW = Math.max(120, width - AXIS_W);
  const plotH = height - AXIS_H - PAD_T;

  const empty = candles.length === 0 || startMs === null || endMs === null;
  let body = null;

  if (!empty && startMs !== null && endMs !== null) {
    const span = endMs - startMs;
    const values: number[] = [];
    for (const c of candles) values.push(c.h, c.l);
    if (isNum(priceToBeat)) values.push(priceToBeat);
    if (isNum(price)) values.push(price);
    let lo = Math.min(...values);
    let hi = Math.max(...values);
    const minRange = 24; // dollars: keeps a quiet minute from looking like a crash
    if (hi - lo < minRange) {
      const mid = (hi + lo) / 2;
      lo = mid - minRange / 2;
      hi = mid + minRange / 2;
    }
    const pad = (hi - lo) * 0.14;
    lo -= pad;
    hi += pad;

    const x = (t: number) => ((t - startMs) / span) * plotW;
    const y = (p: number) => PAD_T + (1 - (p - lo) / (hi - lo)) * plotH;
    const step = niceStep(hi - lo, 4);
    const grid: number[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) grid.push(v);

    const barW = Math.max(2, Math.min(14, (CANDLE_MS / span) * plotW * 0.66));
    const last = candles[candles.length - 1];
    const lastX = x(last.t + CANDLE_MS / 2);
    const diff = isNum(price) && isNum(priceToBeat) ? price - priceToBeat : null;
    const above = diff === null ? true : diff >= 0;
    const tagColor = above ? "var(--up)" : "var(--down)";
    const firstX = x(candles[0].t);

    const tag = (yy: number, text: string, fill: string, textColor: string) => (
      <g>
        <rect x={plotW + 4} y={yy - 10} width={AXIS_W - 6} height="20" rx="6" fill={fill} />
        <text x={plotW + 4 + (AXIS_W - 6) / 2} y={yy + 0.5} textAnchor="middle" dominantBaseline="central" fontSize="11.5" fontWeight="650" fill={textColor}>
          {text}
        </text>
      </g>
    );

    body = (
      <>
        {/* the part of the round this browser has no prices for */}
        {firstX > 6 ? <rect x="0" y={PAD_T} width={firstX} height={plotH} fill="rgba(255,255,255,0.025)" /> : null}

        {grid.map((v) => (
          <g key={v}>
            <line x1="0" x2={plotW} y1={y(v)} y2={y(v)} stroke="rgba(255,255,255,0.06)" />
            <text x={plotW + 8} y={y(v)} dominantBaseline="central" fontSize="11.5" fill="#738990">
              {axisPrice(v)}
            </text>
          </g>
        ))}

        {candles.map((c) => {
          const cx = x(c.t + CANDLE_MS / 2);
          const rising = c.c >= c.o;
          const color = rising ? "var(--up)" : "var(--down)";
          const top = y(Math.max(c.o, c.c));
          const bottom = y(Math.min(c.o, c.c));
          return (
            <g key={c.t}>
              <line x1={cx} x2={cx} y1={y(c.h)} y2={y(c.l)} stroke={color} strokeWidth="1.5" strokeLinecap="round" />
              <rect x={cx - barW / 2} y={top} width={barW} height={Math.max(2, bottom - top)} rx="1.5" fill={color} />
            </g>
          );
        })}

        {isNum(priceToBeat) ? (
          <>
            <line x1="0" x2={plotW} y1={y(priceToBeat)} y2={y(priceToBeat)} stroke="var(--amber)" strokeWidth="1.6" strokeDasharray="6 5" />
            {tag(y(priceToBeat), axisPrice(priceToBeat), "var(--amber)", "#1c1503")}
          </>
        ) : null}

        {isNum(price) ? (
          <>
            <line x1="0" x2={plotW} y1={y(price)} y2={y(price)} stroke={tagColor} strokeOpacity="0.55" strokeWidth="1" />
            {tag(y(price), axisPrice(price), tagColor, "#04140d")}
          </>
        ) : null}

        {diff !== null && isNum(priceToBeat) && isNum(price) ? (
          <g>
            <line x1={lastX + 6} x2={lastX + 6} y1={y(priceToBeat)} y2={y(price)} stroke="rgba(255,255,255,0.45)" strokeWidth="1.5" />
            <g transform={`translate(${Math.max(86, lastX - 12)} ${(y(priceToBeat) + y(price)) / 2})`}>
              <rect x="-78" y="-12" width="76" height="24" rx="8" fill="#0c1c24" stroke="rgba(255,255,255,0.16)" />
              <text x="-40" y="0.5" textAnchor="middle" dominantBaseline="central" fontSize="12" fontWeight="650" fill={above ? "var(--up)" : "var(--down)"}>
                {`${above ? "+" : "-"}${fmtGapUsd(diff)} gap`}
              </text>
            </g>
          </g>
        ) : null}

        {buys.map((buy) => {
          if (buy.t < startMs || buy.t > endMs) return null;
          const bucket = Math.floor(buy.t / CANDLE_MS) * CANDLE_MS;
          const candle = candles.find((c) => c.t === bucket);
          const bx = x(buy.t);
          const by = candle ? Math.min(y(candle.l) + 14, PAD_T + plotH - 5) : PAD_T + plotH - 5;
          return (
            <g key={buy.key}>
              <title>{buy.label}</title>
              <path d={`M ${bx} ${by - 7} L ${bx + 6.5} ${by + 5} L ${bx - 6.5} ${by + 5} Z`} fill={STAGE_VAR[buy.stage]} stroke="#0c1c24" strokeWidth="1.5" />
            </g>
          );
        })}

        {[0, 0.5, 1].map((f) => (
          <text
            key={f}
            x={f * plotW}
            y={PAD_T + plotH + 18}
            textAnchor={f === 0 ? "start" : f === 1 ? "end" : "middle"}
            fontSize="11.5"
            fill="#738990"
          >
            {clock(startMs + f * span)}
          </text>
        ))}
      </>
    );
  }

  return (
    <div
      ref={ref}
      className={`${fill ? "absolute inset-0" : "relative w-full"} overflow-hidden rounded-[var(--radius-tile)] border hairline`}
      style={fill ? undefined : { height }}
    >
      <div
        className="pointer-events-none absolute inset-0"
        style={{ background: "linear-gradient(180deg, rgba(61,220,151,0.09), rgba(61,220,151,0.015) 70%, transparent)" }}
      />
      <svg width={width} height={height} className="relative block" role="img" aria-label="BTC price in 5 second candles for this round, with the price to beat line">
        {body}
      </svg>
      {empty ? (
        <div className="absolute inset-0 flex flex-col items-center justify-center px-6 text-center">
          <div className="text-[14px] font-medium text-slate-300">No BTC prices in this round yet</div>
          <div className="mt-1 max-w-sm text-[12.5px] text-slate-500">
            Candles are built in this browser from the live Chainlink price, so they start with the first price received.
          </div>
        </div>
      ) : null}
    </div>
  );
}
