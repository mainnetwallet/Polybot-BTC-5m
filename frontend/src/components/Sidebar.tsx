import { useState } from "react";
import { NAV, type Page } from "../lib/navigation";
import { useBot } from "../hooks/BotContext";
import { useAuth } from "../hooks/AuthContext";
import { cx } from "../lib/format";
import { IconChevronsLeft, IconChevronsRight, IconLogout, IconUser } from "./Icons";

interface SidebarProps {
  page: Page;
  onNavigate: (page: Page) => void;
}

const STORAGE_KEY = "polybot.sidebar.expanded";

function readExpanded(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * The left rail (large screens). Collapsed it is a 64px icon rail with tooltips; expanded it is a 216px
 * sidebar that shows each icon with its name. The choice is remembered. Below 1024px the Topbar shows the
 * same pages as a tab strip.
 */
export function Sidebar({ page, onNavigate }: SidebarProps) {
  const { feedState } = useBot();
  const { authRequired, username, logout } = useAuth();
  const [expanded, setExpanded] = useState(readExpanded);

  const toggle = () => {
    setExpanded((prev) => {
      const next = !prev;
      try {
        window.localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
      } catch {
        /* storage can be blocked; the choice just won't be remembered */
      }
      return next;
    });
  };

  const feedText = feedState === "open" ? "Feed connected" : feedState === "connecting" ? "Connecting" : "Feed offline";
  const signOutText = `Sign out${username ? ` (${username})` : ""}`;

  // every row is the full width of the rail minus the 10px side padding: 44px collapsed, 196px expanded
  const row = "rail-item group relative flex h-11 w-full items-center rounded-[14px] transition-colors";

  return (
    <aside
      className={cx(
        "hidden shrink-0 flex-col border-r hairline bg-[var(--bg-rail)] py-4 transition-[width] duration-200 ease-out lg:flex",
        expanded ? "w-[var(--rail-w-open)]" : "w-[var(--rail-w)]",
      )}
      aria-label="Main navigation"
    >
      <div className="flex h-11 items-center gap-3 overflow-hidden px-[10px]" title="Polybot">
        <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[14px] bg-gradient-to-br from-[#5eead4] to-[#3b9ff2] text-[#04141c]">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M3 17l6-6 4 4 8-8" />
            <path d="M15 7h6v6" />
          </svg>
        </div>
        {expanded ? <span className="whitespace-nowrap text-[16px] font-semibold tracking-tight text-slate-100">Polybot</span> : null}
      </div>

      <nav className="mt-6 flex flex-col gap-2 px-[10px]">
        <button
          type="button"
          onClick={toggle}
          aria-expanded={expanded}
          aria-label={expanded ? "Collapse sidebar" : "Expand sidebar"}
          className={cx(row, "border border-white/[0.06] text-slate-400 hover:bg-white/[0.05] hover:text-slate-100")}
        >
          <span className="flex h-full w-[42px] shrink-0 items-center justify-center">
            {expanded ? <IconChevronsLeft size={18} /> : <IconChevronsRight size={18} />}
          </span>
          {expanded ? (
            <span className="truncate pr-3 text-[14px] font-medium">Collapse</span>
          ) : (
            <span className="rail-tip" role="tooltip">
              Expand
            </span>
          )}
        </button>
        <div className="mx-1 my-1 h-px bg-white/[0.06]" aria-hidden="true" />
        {NAV.map((item) => {
          const Icon = item.icon;
          const active = item.id === page;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate(item.id)}
              aria-label={item.label}
              aria-current={active ? "page" : undefined}
              className={cx(
                row,
                "border",
                active
                  ? "border-white/10 bg-white/[0.06] text-slate-100"
                  : "border-transparent text-slate-500 hover:bg-white/[0.04] hover:text-slate-200",
              )}
            >
              {active ? <span className="absolute -left-[10px] top-2.5 bottom-2.5 w-[3px] rounded-r-full bg-up" aria-hidden="true" /> : null}
              <span className="flex h-full w-[42px] shrink-0 items-center justify-center">
                <Icon size={19} />
              </span>
              {expanded ? (
                <span className="truncate pr-3 text-[14px] font-medium">{item.label}</span>
              ) : (
                <span className="rail-tip" role="tooltip">
                  {item.label}
                </span>
              )}
            </button>
          );
        })}
      </nav>

      <div className="mt-auto flex flex-col gap-2 px-[10px]">
        <div className={cx("flex h-8 items-center", expanded ? "gap-2 pl-[17px]" : "justify-center")}
          title={feedText}
          role="img"
          aria-label={feedText}
        >
          <span
            className={cx(
              "h-2 w-2 shrink-0 rounded-full",
              feedState === "open" ? "live-dot bg-up" : feedState === "connecting" ? "bg-amber" : "bg-down",
            )}
          />
          {expanded ? <span className="truncate text-[12px] text-slate-500">{feedText}</span> : null}
        </div>

        {authRequired ? (
          <button
            type="button"
            onClick={() => void logout()}
            className={cx(row, "text-slate-500 hover:bg-white/[0.04] hover:text-slate-200")}
            aria-label={signOutText}
          >
            <span className="flex h-full w-[44px] shrink-0 items-center justify-center">
              <IconLogout size={18} />
            </span>
            {expanded ? (
              <span className="truncate pr-3 text-[14px] font-medium">{signOutText}</span>
            ) : (
              <span className="rail-tip" role="tooltip">
                {signOutText}
              </span>
            )}
          </button>
        ) : (
          <div className="flex h-11 w-full items-center text-slate-600" title="Local session">
            <span className="flex h-full w-[44px] shrink-0 items-center justify-center">
              <IconUser size={18} />
            </span>
            {expanded ? <span className="truncate text-[13px]">Local session</span> : null}
          </div>
        )}
      </div>
    </aside>
  );
}
