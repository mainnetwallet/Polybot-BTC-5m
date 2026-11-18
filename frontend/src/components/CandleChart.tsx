import { useEffect, useRef, useState } from "react";
import type { Candle } from "../hooks/BtcTape";
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
  /** length of one candle in ms (5 s, 1 min or 5 min) */
  candleMs: number;
  /** left edge of the chart: the oldest round shown */
  startMs: number | null;
  /** right edge of the chart: the end of the current round */
  endMs: number | null;
  /** start of the current round, where its price to beat applies from */
  roundStartMs: number | null;
  priceToBeat: number | null;
  /** latest BTC price, drawn as the tag on the right axis */
  price: number | null;
  buys: BuyMarker[];
  height?: number;
  /** fill the parent box (which must be `relative` with a height) instead of using a fixed height */
  fill?: boolean;
}

const ROUND_MS = 300_000;
const AXIS_W = 66;
const AXIS_H = 26;
const PAD_T = 10;
const LABEL_STEPS = [10_000, 30_000, 60_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000];

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

function clock(ms: number, seconds: boolean): string {
  return new Date(ms).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    ...(seconds ? { second: "2-digit" } : {}),
    hour12: false,
  });
}

function axisPrice(value: number): string {
  return Math.round(value).toLocaleString("en-US");
}

function price2(value: number): string {
  return value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * Exchange-style candles (solid bodies, thin wicks) over the last few rounds, with the dashed price to beat
 * line of the current round, round boundaries, a hover read-out and a marker for every buy.
 */
export function CandleChart({
  candles,
  candleMs,
  startMs,
  endMs,
  roundStartMs,
  priceToBeat,
  price,
  buys,
  height: fixedHeight = 330,
  fill = false,
}: CandleChartProps) {
  const { ref, width, measuredHeight } = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const height = fill ? Math.max(150, measuredHeight) : fixedHeight;
  const plotW = Math.max(120, width - AXIS_W);
  const plotH = height - AXIS_H - PAD_T;

  const empty = candles.length === 0 || startMs === null || endMs === null;
  let body = null;

  if (!empty && startMs !== null && endMs !== null) {
    const span = endMs - startMs;
    const beat = isNum(priceToBeat) && roundStartMs !== null ? priceToBeat : null;
    const values: number[] = [];
    for (const c of candles) values.push(c.h, c.l);
    if (beat !== null) values.push(beat);
    if (isNum(price)) values.push(price);
    let lo = Math.min(...values);
    let hi = Math.max(...values);
    const minRange = 24; // dollars: keeps a quiet minute from looking like a crash
    if (hi - lo < minRange) {
      const mid = (hi + lo) / 2;
      lo = mid - minRange / 2;
      hi = mid + minRange / 2;
    }
    const pad = (hi - lo) * 0.12;
    lo -= pad;
    hi += pad;

    const x = (t: number) => ((t - startMs) / span) * plotW;
    const y = (p: number) => PAD_T + (1 - (p - lo) / (hi - lo)) * plotH;
    const step = niceStep(hi - lo, 5);
    const grid: number[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) grid.push(v);

    // time labels: the smallest nice step that keeps them at least 84 px apart
    const labelStep = LABEL_STEPS.find((s) => (s / span) * plotW >= 84) ?? LABEL_STEPS[LABEL_STEPS.length - 1];
    const labels: number[] = [];
    for (let t = Math.ceil(startMs / labelStep) * labelStep; t <= endMs; t += labelStep) labels.push(t);
    const showSeconds = labelStep < 60_000;

    // round boundaries (every 5 minutes, aligned to the current round)
    const anchor = roundStartMs ?? endMs - ROUND_MS;
    const boundaries: number[] = [];
    for (let t = anchor - Math.floor((anchor - startMs) / ROUND_MS) * ROUND_MS; t < endMs; t += ROUND_MS) {
      if (t > startMs) boundaries.push(t);
    }

    const slotW = (candleMs / span) * plotW;
    const barW = Math.max(1, Math.min(22, slotW * 0.72));
    const last = candles[candles.length - 1];
    const lastX = x(last.t + candleMs / 2);
    const diff = isNum(price) && beat !== null ? price - beat : null;
    const above = diff === null ? true : diff >= 0;
    const tagColor = above ? "var(--up)" : "var(--down)";
    const firstX = x(candles[0].t);
    const roundX = roundStartMs !== null ? Math.max(0, x(roundStartMs)) : null;

    const tag = (yy: number, text: string, bg: string, textColor: string) => (
      <g>
        <rect x={plotW + 4} y={yy - 10} width={AXIS_W - 6} height="20" rx="6" fill={bg} />
        <text x={plotW + 4 + (AXIS_W - 6) / 2} y={yy + 0.5} textAnchor="middle" dominantBaseline="central" fontSize="11.5" fontWeight="650" fill={textColor}>
          {text}
        </text>
      </g>
    );

    const hovered = hover !== null ? candles[hover] : null;

    body = (
      <>
        {/* the current round is tinted, and the part of the chart this browser has no prices for is shaded */}
        {roundX !== null ? <rect x={roundX} y={PAD_T} width={Math.max(0, plotW - roundX)} height={plotH} fill="rgba(255,255,255,0.022)" /> : null}
        {firstX > 6 ? <rect x="0" y={PAD_T} width={firstX} height={plotH} fill="rgba(255,255,255,0.025)" /> : null}

        {grid.map((v) => (
          <g key={v}>
            <line x1="0" x2={plotW} y1={y(v)} y2={y(v)} stroke="rgba(255,255,255,0.055)" />
            <text x={plotW + 8} y={y(v)} dominantBaseline="central" fontSize="11.5" fill="#738990">
              {axisPrice(v)}
            </text>
          </g>
        ))}

        {labels.map((t) => (
          <line key={`g${t}`} x1={x(t)} x2={x(t)} y1={PAD_T} y2={PAD_T + plotH} stroke="rgba(255,255,255,0.04)" />
        ))}
        {boundaries.map((t) => (
          <line key={`b${t}`} x1={x(t)} x2={x(t)} y1={PAD_T} y2={PAD_T + plotH} stroke="rgba(255,255,255,0.16)" strokeDasharray="2 4" />
        ))}

        {candles.map((c) => {
          const cx = Math.round(x(c.t + candleMs / 2)) + 0.5;
          const rising = c.c >= c.o;
          const color = rising ? "var(--up)" : "var(--down)";
          const top = y(Math.max(c.o, c.c));
          const bottom = y(Math.min(c.o, c.c));
          return (
            <g key={c.t} shapeRendering="crispEdges">
              <line x1={cx} x2={cx} y1={y(c.h)} y2={y(c.l)} stroke={color} strokeWidth="1" />
              <rect x={cx - barW / 2} y={top} width={barW} height={Math.max(1, bottom - top)} fill={color} />
            </g>
          );
        })}

        {beat !== null && roundStartMs !== null ? (
          <>
            <line x1={Math.max(0, x(roundStartMs))} x2={plotW} y1={y(beat)} y2={y(beat)} stroke="var(--amber)" strokeWidth="1.5" strokeDasharray="6 5" />
            {tag(y(beat), axisPrice(beat), "var(--amber)", "#1c1503")}
          </>
        ) : null}

        {isNum(price) ? (
          <>
            <line x1="0" x2={plotW} y1={y(price)} y2={y(price)} stroke={tagColor} strokeOpacity="0.55" strokeWidth="1" strokeDasharray="1 3" />
            {tag(y(price), axisPrice(price), tagColor, "#04140d")}
          </>
        ) : null}

        {diff !== null && beat !== null && isNum(price) ? (
          <g>
            <line x1={lastX + 6} x2={lastX + 6} y1={y(beat)} y2={y(price)} stroke="rgba(255,255,255,0.45)" strokeWidth="1.5" />
            <g transform={`translate(${Math.max(86, Math.min(plotW - 8, lastX - 12))} ${(y(beat) + y(price)) / 2})`}>
              <rect x="-78" y="-12" width="76" height="24" rx="8" fill="#0c1c24" stroke="rgba(255,255,255,0.16)" />
              <text x="-40" y="0.5" textAnchor="middle" dominantBaseline="central" fontSize="12" fontWeight="650" fill={above ? "var(--up)" : "var(--down)"}>
                {`${above ? "+" : "-"}${fmtGapUsd(diff)} gap`}
              </text>
            </g>
          </g>
        ) : null}

        {buys.map((buy) => {
          if (buy.t < startMs || buy.t > endMs) return null;
          const bucket = Math.floor(buy.t / candleMs) * candleMs;
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

        {labels.map((t, i) => (
          <text
            key={t}
            x={x(t)}
            y={PAD_T + plotH + 18}
            textAnchor={i === 0 && x(t) < 24 ? "start" : "middle"}
            fontSize="11.5"
            fill="#738990"
          >
            {clock(t, showSeconds)}
          </text>
        ))}

        {hovered ? (
          <g pointerEvents="none">
            <line
              x1={x(hovered.t + candleMs / 2)}
              x2={x(hovered.t + candleMs / 2)}
              y1={PAD_T}
              y2={PAD_T + plotH}
              stroke="rgba(255,255,255,0.28)"
              strokeDasharray="3 3"
            />
            <text x="8" y={PAD_T + 12} fontSize="11.5" fill="#c6d4d8">
              <tspan fontWeight="650">{clock(hovered.t, candleMs < 60_000)}</tspan>
              <tspan dx="10" fill="#738990">O </tspan>
              <tspan>{price2(hovered.o)}</tspan>
              <tspan dx="8" fill="#738990">H </tspan>
              <tspan>{price2(hovered.h)}</tspan>
              <tspan dx="8" fill="#738990">L </tspan>
              <tspan>{price2(hovered.l)}</tspan>
              <tspan dx="8" fill="#738990">C </tspan>
              <tspan fill={hovered.c >= hovered.o ? "var(--up)" : "var(--down)"}>{price2(hovered.c)}</tspan>
            </text>
          </g>
        ) : null}
      </>
    );
  }

  const onMove = (event: React.MouseEvent<SVGSVGElement>) => {
    if (empty || startMs === null || endMs === null) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / rect.width) * width;
    if (px < 0 || px > plotW) {
      setHover(null);
      return;
    }
    const t = startMs + (px / plotW) * (endMs - startMs);
    const bucket = Math.floor(t / candleMs) * candleMs;
    const index = candles.findIndex((c) => c.t === bucket);
    setHover(index >= 0 ? index : null);
  };

  return (
    <div
      ref={ref}
      className={`${fill ? "absolute inset-0" : "relative w-full"} overflow-hidden rounded-[var(--radius-tile)] border hairline`}
      style={fill ? undefined : { height }}
    >
      <div
        className="pointer-events-none absolute inset-0"
        style={{ background: "linear-gradient(180deg, rgba(61,220,151,0.07), rgba(61,220,151,0.012) 70%, transparent)" }}
      />
      <svg
        width={width}
        height={height}
        className="relative block"
        role="img"
        aria-label="BTC price candles for the last rounds, with the price to beat line of the current round"
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        {body}
      </svg>
      {empty ? (
        <div className="absolute inset-0 flex flex-col items-center justify-center px-6 text-center">
          <div className="text-[14px] font-medium text-slate-300">No BTC prices yet</div>
          <div className="mt-1 max-w-sm text-[12.5px] text-slate-500">
            Candles are drawn from the live Chainlink price. They appear as soon as the first price arrives.
          </div>
        </div>
      ) : null}
    </div>
  );
}
