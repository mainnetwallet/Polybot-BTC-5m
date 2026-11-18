import type { ReactNode } from "react";
import { cx } from "../lib/format";

interface CardProps {
  title: string;
  right?: ReactNode;
  className?: string;
  children: ReactNode;
  /** extra classes for the body wrapper */
  bodyClassName?: string;
}

/** The standard dashboard card: title on the left, a small note or chip on the right. */
export function Card({ title, right, className, children, bodyClassName }: CardProps) {
  return (
    <section className={cx("panel flex min-w-0 flex-col p-5", className)}>
      <header className="card-head mb-4 flex items-start justify-between gap-3">
        <h2 className="card-title">{title}</h2>
        {right ? <div className="flex shrink-0 items-center gap-2 text-[13px] text-slate-500">{right}</div> : null}
      </header>
      <div className={cx("flex min-h-0 flex-1 flex-col", bodyClassName)}>{children}</div>
    </section>
  );
}
