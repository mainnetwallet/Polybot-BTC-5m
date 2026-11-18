import type { ReactNode } from "react";
import { fmtClock } from "../lib/format";
import { STAGE_VAR, stageStatus, type StageWindow } from "../lib/stages";

interface RoundRingProps {
  /** seconds left in the round, null = no active market */
  remaining: number | null;
  /** round length in seconds (300 for the 5 minute market) */
  duration: number | null;
  windows: StageWindow[];
  /** the chip under the countdown ("Up is ahead") */
  chip?: ReactNode;
}

const C = 220; // viewBox center
const R_OUT = 186; // outer lane radius
const R_IN = 160; // inner lane, used when two stage windows overlap
const R_DIAL = 140;

function polar(radius: number, deg: number): [number, number] {
  const rad = (deg * Math.PI) / 180;
  return [C + radius * Math.sin(rad), C - radius * Math.cos(rad)];
}

function arcPath(radius: number, from: number, to: number): string {
  const [x1, y1] = polar(radius, from);
  const [x2, y2] = polar(radius, to);
  const large = to - from > 180 ? 1 : 0;
  return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${radius} ${radius} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
}

/**
 * The round as a clock face. The top is the end of the round; time runs clockwise, so a stage window that
 * opens at "90 s left" sits at 70 % of the way around. Each enabled stage is its own colored arc, the
 * one that is open glows, finished ones fade, and the dot is "now".
 */
export function RoundRing({ remaining, duration, windows, chip }: RoundRingProps) {
  const total = duration && duration > 0 ? duration : 300;
  const angleAt = (secondsLeft: number) => 360 * (1 - Math.min(Math.max(secondsLeft, 0), total) / total);
  const nowAngle = remaining === null ? null : angleAt(remaining);

  // a window that overlaps an earlier one moves to the inner lane so both stay readable
  const placed: Array<{ w: StageWindow; lane: 0 | 1 }> = [];
  for (const w of windows) {
    if (!w.enabled) continue;
    const overlaps = placed.some(
      ({ w: other, lane }) => lane === 0 && Math.min(other.start, w.start) > Math.max(other.end, w.end),
    );
    placed.push({ w, lane: overlaps ? 1 : 0 });
  }

  const labels = [1, 2, 3, 4].map((minute) => minute * 60).filter((seconds) => seconds < total);
  const ticks: number[] = [];
  for (let s = 0; s < total; s += 15) ticks.push(s);

  const active = windows.find((w) => stageStatus(w, remaining).kind === "watching");
  const summary =
    remaining === null
      ? "Round clock, no active market"
      : `Round clock, ${fmtClock(remaining)} left${active ? `, stage ${active.stage} watching` : ""}`;

  return (
    <div className="relative mx-auto w-full max-w-[440px]" style={{ containerType: "inline-size" }}>
      <svg viewBox="-12 -12 464 464" className="block h-auto w-full" role="img" aria-label={summary}>
        <defs>
          <radialGradient id="ring-dial" cx="50%" cy="45%" r="60%">
            <stop offset="0%" stopColor="#123039" />
            <stop offset="100%" stopColor="#0c1c24" />
          </radialGradient>
          <filter id="ring-glow" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="4" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* tracks and dial */}
        <circle cx={C} cy={C} r={R_OUT} fill="none" stroke="rgba(255,255,255,0.045)" strokeWidth="16" />
        <circle cx={C} cy={C} r={R_IN} fill="none" stroke="rgba(255,255,255,0.025)" strokeWidth="8" />
        <circle cx={C} cy={C} r={R_DIAL} fill="url(#ring-dial)" stroke="rgba(255,255,255,0.06)" />

        {/* minute and 15 second ticks, time left labels */}
        {ticks.map((seconds) => {
          const major = seconds % 60 === 0;
          const a = angleAt(seconds);
          const [x1, y1] = polar(R_OUT + 14, a);
          const [x2, y2] = polar(R_OUT + (major ? 22 : 18), a);
          return (
            <line
              key={seconds}
              x1={x1}
              y1={y1}
              x2={x2}
              y2={y2}
              stroke={major ? "rgba(255,255,255,0.22)" : "rgba(255,255,255,0.1)"}
              strokeWidth="1.5"
              strokeLinecap="round"
            />
          );
        })}
        {labels.map((seconds) => {
          const [x, y] = polar(R_OUT + 36, angleAt(seconds));
          return (
            <text key={seconds} x={x} y={y} fill="#86999f" fontSize="13" fontWeight="500" textAnchor="middle" dominantBaseline="central">
              {fmtClock(seconds)}
            </text>
          );
        })}

        {/* stage arcs */}
        {placed.map(({ w, lane }) => {
          const status = stageStatus(w, remaining).kind;
          const radius = lane === 0 ? R_OUT : R_IN;
          const width = lane === 0 ? 13 : 8;
          const a1 = angleAt(w.start);
          const a2 = angleAt(Math.max(w.end, 0));
          const color = STAGE_VAR[w.stage];
          const opacity = status === "watching" ? 1 : status === "done" ? 0.32 : 0.78;
          const [bx, by] = polar(radius, a1);
          return (
            <g key={w.stage} opacity={opacity}>
              <path
                d={arcPath(radius, a1, Math.max(a2, a1 + 0.5))}
                fill="none"
                stroke={color}
                strokeWidth={width}
                strokeLinecap="round"
                filter={status === "watching" ? "url(#ring-glow)" : undefined}
              />
              <circle cx={bx} cy={by} r={lane === 0 ? 10.5 : 9} fill="#0c1c24" stroke={color} strokeWidth="2" />
              <text x={bx} y={by + 0.5} fill={color} fontSize="11.5" fontWeight="700" textAnchor="middle" dominantBaseline="central">
                {w.stage}
              </text>
            </g>
          );
        })}

        {/* now */}
        {nowAngle !== null ? (
          <g>
            <line
              x1={polar(R_DIAL - 38, nowAngle)[0]}
              y1={polar(R_DIAL - 38, nowAngle)[1]}
              x2={polar(R_OUT - 12, nowAngle)[0]}
              y2={polar(R_OUT - 12, nowAngle)[1]}
              stroke="rgba(245,181,68,0.55)"
              strokeWidth="2"
              strokeLinecap="round"
            />
            <circle cx={polar(R_OUT, nowAngle)[0]} cy={polar(R_OUT, nowAngle)[1]} r="9" fill="#f5b544" stroke="#fff3d6" strokeWidth="2.5" filter="url(#ring-glow)" />
          </g>
        ) : null}
      </svg>

      <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
        {remaining === null ? (
          <>
            <div className="text-[7cqw] font-semibold text-slate-300">No active round</div>
            <div className="mt-1 text-[3.4cqw] text-slate-500">The next market is picked up automatically</div>
          </>
        ) : (
          <>
            <div className="hero-num" style={{ fontSize: "17cqw" }}>
              {fmtClock(remaining)}
            </div>
            <div className="mt-[1.4cqw] text-[3.5cqw] text-slate-500">left in this round</div>
            {chip ? <div className="mt-[3cqw]">{chip}</div> : null}
          </>
        )}
      </div>
    </div>
  );
}
