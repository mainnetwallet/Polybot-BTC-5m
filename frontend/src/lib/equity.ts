import type { PositionRow } from "../api/types";

/** Cumulative realized P&L over the closed positions, oldest first, starting at 0. */
export function realizedCurve(rows: PositionRow[]): { points: number[]; count: number } {
  const closed = rows
    .filter((row) => row.status !== "open" && row.closed_at)
    .sort((a, b) => Date.parse(a.closed_at ?? "") - Date.parse(b.closed_at ?? ""));
  let total = 0;
  const points = [0];
  for (const row of closed) {
    total += row.realized_pnl;
    points.push(total);
  }
  return { points, count: closed.length };
}
