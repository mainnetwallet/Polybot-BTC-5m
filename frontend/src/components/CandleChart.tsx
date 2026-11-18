import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
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
  /** every candle there is, oldest first; the chart shows as many as fit and scrolls back through the rest */
  candles: Candle[];
  /** length of one candle in ms (5 s, 1 min or 5 min) */
  candleMs: number;
  /** width of one candle slot in px: candles keep this size however many there are */
  slotPx?: number;
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
const RIGHT_SLOTS = 6; // empty room right of the newest candle, like on an exchange chart
const MIN_VISIBLE_SLOTS = 6; // scrolling back stops with this many candles still in view
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
 * Exchange-style candles (solid bodies, thin wicks) that keep a fixed size. The newest candle is on the right;
 * drag the chart (or use the horizontal wheel / shift + wheel) to scroll back through the history, and press
 * "Latest" to return. The current round's dashed price to beat line, round boundaries, a hover read-out and a
 * marker for every buy are drawn on top.
 */
export function CandleChart({
  candles,
  candleMs,
  slotPx = 10,
  roundStartMs,
  priceToBeat,
  price,
  buys,
  height: fixedHeight = 330,
  fill = false,
}: CandleChartProps) {
  const { ref, width, measuredHeight } = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  /** right edge of the view when scrolled back (absolute time); null = following the newest candle */
  const [pinnedEnd, setPinnedEnd] = useState<number | null>(null);
  const [dragging, setDragging] = useState(false);
  const drag = useRef<{ x: number; end: number } | null>(null);
  const height = fill ? Math.max(150, measuredHeight) : fixedHeight;
  const plotW = Math.max(120, width - AXIS_W);
  const plotH = height - AXIS_H - PAD_T;

  const empty = candles.length === 0;
  const first = candles[0];
  const last = candles[candles.length - 1];
  const span = (plotW / slotPx) * candleMs;
  const liveEnd = last ? last.t + candleMs * (1 + RIGHT_SLOTS) : 0;
  const minEnd = first ? first.t + candleMs * MIN_VISIBLE_SLOTS : 0;
  const clampEnd = (end: number) => Math.min(liveEnd, Math.max(minEnd, end));
  const viewEnd = pinnedEnd === null ? liveEnd : clampEnd(pinnedEnd);
  const viewStart = viewEnd - span;
  const live = pinnedEnd === null || viewEnd >= liveEnd - candleMs / 2;

  // a new window width or candle size must not leave the view pinned past the data
  useEffect(() => {
    setPinnedEnd((prev) => (prev !== null && prev >= liveEnd - candleMs / 2 ? null : prev));
  }, [liveEnd, candleMs]);

  const scrollBy = (deltaMs: number) => {
    // positive = back in time
    const next = clampEnd(viewEnd - deltaMs);
    setPinnedEnd(next >= liveEnd - candleMs / 2 ? null : next);
  };

  // horizontal wheel or shift + wheel scrolls the chart; a plain vertical wheel keeps scrolling the page
  const wheelHost = useRef<HTMLDivElement | null>(null);
  const latest = useRef({ scrollBy, slotPx, candleMs });
  latest.current = { scrollBy, slotPx, candleMs };
  useEffect(() => {
    const node = wheelHost.current;
    if (!node) return;
    const onWheel = (event: WheelEvent) => {
      const horizontal = Math.abs(event.deltaX) > Math.abs(event.deltaY) || event.shiftKey;
      if (!horizontal) return;
      event.preventDefault();
      const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
      const { scrollBy: by, slotPx: slot, candleMs: ms } = latest.current;
      by((-delta / slot) * ms);
    };
    node.addEventListener("wheel", onWheel, { passive: false });
    return () => node.removeEventListener("wheel", onWheel);
  }, []);

  let body = null;

  if (!empty) {
    const roundVisible = roundStartMs !== null && roundStartMs < viewEnd;
    const beat = isNum(priceToBeat) && roundVisible ? priceToBeat : null;
    const newestVisible = last.t < viewEnd;
    const shown = candles.filter((c) => c.t + candleMs > viewStart && c.t < viewEnd);

    const values: number[] = [];
    for (const c of shown) values.push(c.h, c.l);
    if (beat !== null) values.push(beat);
    if (isNum(price) && newestVisible) values.push(price);
    if (values.length === 0) values.push(last.c);
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

    const x = (t: number) => ((t - viewStart) / span) * plotW;
    const y = (p: number) => PAD_T + (1 - (p - lo) / (hi - lo)) * plotH;
    const inPlot = (yy: number) => yy >= PAD_T && yy <= PAD_T + plotH;
    const step = niceStep(hi - lo, 5);
    const grid: number[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) grid.push(v);

    // time labels: the smallest nice step that keeps them at least 84 px apart
    const labelStep = LABEL_STEPS.find((s) => (s / candleMs) * slotPx >= 84) ?? LABEL_STEPS[LABEL_STEPS.length - 1];
    const labels: number[] = [];
    for (let t = Math.ceil(viewStart / labelStep) * labelStep; t <= viewEnd; t += labelStep) labels.push(t);
    const showSeconds = labelStep < 60_000;

    // round boundaries: every 5 minutes, aligned to the market windows
    const boundaries: number[] = [];
    for (let t = Math.ceil(viewStart / ROUND_MS) * ROUND_MS; t < viewEnd; t += ROUND_MS) boundaries.push(t);

    const barW = Math.max(1, Math.min(22, slotPx * 0.7));
    const lastX = x(last.t + candleMs / 2);
    const diff = isNum(price) && beat !== null ? price - beat : null;
    const above = diff === null ? true : diff >= 0;
    const tagColor = above ? "var(--up)" : "var(--down)";
    const firstX = x(first.t);
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
        {/* the current round is tinted, and time before the first saved price is shaded */}
        {roundVisible && roundX !== null && roundX < plotW ? (
          <rect x={roundX} y={PAD_T} width={Math.max(0, plotW - roundX)} height={plotH} fill="rgba(255,255,255,0.022)" />
        ) : null}
        {firstX > 0 ? <rect x="0" y={PAD_T} width={Math.min(plotW, firstX)} height={plotH} fill="rgba(255,255,255,0.025)" /> : null}

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

        {shown.map((c) => {
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

        {beat !== null && roundStartMs !== null && inPlot(y(beat)) ? (
          <>
            <line x1={Math.max(0, x(roundStartMs))} x2={plotW} y1={y(beat)} y2={y(beat)} stroke="var(--amber)" strokeWidth="1.5" strokeDasharray="6 5" />
            {tag(y(beat), axisPrice(beat), "var(--amber)", "#1c1503")}
          </>
        ) : null}

        {isNum(price) && newestVisible && inPlot(y(price)) ? (
          <>
            <line x1="0" x2={plotW} y1={y(price)} y2={y(price)} stroke={tagColor} strokeOpacity="0.55" strokeWidth="1" strokeDasharray="1 3" />
            {tag(y(price), axisPrice(price), tagColor, "#04140d")}
          </>
        ) : null}

        {diff !== null && beat !== null && isNum(price) && newestVisible && inPlot(y(beat)) && inPlot(y(price)) ? (
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
          if (buy.t < viewStart || buy.t > viewEnd) return null;
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
          <text key={t} x={x(t)} y={PAD_T + plotH + 18} textAnchor={i === 0 && x(t) < 24 ? "start" : "middle"} fontSize="11.5" fill="#738990">
            {clock(t, showSeconds)}
          </text>
        ))}

        {hovered && !dragging ? (
          <g pointerEvents="none">
            <line x1={x(hovered.t + candleMs / 2)} x2={x(hovered.t + candleMs / 2)} y1={PAD_T} y2={PAD_T + plotH} stroke="rgba(255,255,255,0.28)" strokeDasharray="3 3" />
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

  // the chart can sit inside a scaled container, so pointer distances are converted from screen px to layout px
  const layoutScale = (el: Element) => {
    const rect = el.getBoundingClientRect();
    return rect.width > 0 ? width / rect.width : 1;
  };

  const onPointerDown = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (empty || event.button !== 0) return;
    drag.current = { x: event.clientX, end: viewEnd };
    setDragging(true);
    setHover(null);
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const onPointerMove = (event: ReactPointerEvent<SVGSVGElement>) => {
    if (empty) return;
    const scale = layoutScale(event.currentTarget);
    if (drag.current) {
      const dx = (event.clientX - drag.current.x) * scale;
      const next = clampEnd(drag.current.end - (dx / slotPx) * candleMs);
      setPinnedEnd(next >= liveEnd - candleMs / 2 ? null : next);
      return;
    }
    const rect = event.currentTarget.getBoundingClientRect();
    const px = (event.clientX - rect.left) * scale;
    if (px < 0 || px > plotW) {
      setHover(null);
      return;
    }
    const bucket = Math.floor((viewStart + (px / plotW) * span) / candleMs) * candleMs;
    const index = candles.findIndex((c) => c.t === bucket);
    setHover(index >= 0 ? index : null);
  };

  const endDrag = (event: ReactPointerEvent<SVGSVGElement>) => {
    drag.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  return (
    <div
      ref={(node) => {
        ref.current = node;
        wheelHost.current = node;
      }}
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
        className="relative block touch-pan-y select-none"
        style={{ cursor: empty ? "default" : dragging ? "grabbing" : "grab" }}
        role="img"
        aria-label="BTC price candles; drag sideways to scroll back through earlier candles"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onMouseLeave={() => setHover(null)}
      >
        {body}
      </svg>
      {!empty && !live ? (
        <button
          type="button"
          onClick={() => setPinnedEnd(null)}
          className="absolute right-[74px] top-2 rounded-md border border-white/15 bg-[#0c1c24]/90 px-2.5 py-1 text-[12px] font-semibold text-slate-100 hover:bg-[#12303a]"
        >
          Latest ›
        </button>
      ) : null}
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
