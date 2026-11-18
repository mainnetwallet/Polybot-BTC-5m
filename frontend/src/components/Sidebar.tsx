import { NAV, type Page } from "../lib/navigation";
import { useBot } from "../hooks/BotContext";
import { useAuth } from "../hooks/AuthContext";
import { cx } from "../lib/format";
import { IconLogout, IconUser } from "./Icons";

interface SidebarProps {
  page: Page;
  onNavigate: (page: Page) => void;
}

/** The 64px icon rail (large screens). Below 1024px the Topbar shows the same pages as a tab strip. */
export function Sidebar({ page, onNavigate }: SidebarProps) {
  const { feedState } = useBot();
  const { authRequired, username, logout } = useAuth();

  return (
    <aside
      className="hidden w-[var(--rail-w)] shrink-0 flex-col items-center border-r hairline bg-[var(--bg-rail)] py-4 lg:flex"
      aria-label="Main navigation"
    >
      <div
        className="flex h-11 w-11 items-center justify-center rounded-[14px] bg-gradient-to-br from-[#5eead4] to-[#3b9ff2] text-[#04141c]"
        title="Polybot"
      >
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M3 17l6-6 4 4 8-8" />
          <path d="M15 7h6v6" />
        </svg>
      </div>

      <nav className="mt-8 flex flex-col items-center gap-2">
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
                "rail-item group relative flex h-11 w-11 items-center justify-center rounded-[14px] border transition-colors",
                active
                  ? "border-white/10 bg-white/[0.06] text-slate-100"
                  : "border-transparent text-slate-500 hover:bg-white/[0.04] hover:text-slate-200",
              )}
            >
              {active ? <span className="absolute -left-[10px] top-2.5 bottom-2.5 w-[3px] rounded-r-full bg-up" aria-hidden="true" /> : null}
              <Icon size={19} />
              <span className="rail-tip" role="tooltip">
                {item.label}
              </span>
            </button>
          );
        })}
      </nav>

      <div className="mt-auto flex flex-col items-center gap-3">
        <span
          className={cx(
            "h-2 w-2 rounded-full",
            feedState === "open" ? "live-dot bg-up" : feedState === "connecting" ? "bg-amber" : "bg-down",
          )}
          title={feedState === "open" ? "Realtime feed connected" : feedState === "connecting" ? "Connecting to the backend" : "Backend feed offline"}
          role="img"
          aria-label={feedState === "open" ? "Realtime feed connected" : feedState === "connecting" ? "Connecting" : "Feed offline"}
        />
        {authRequired ? (
          <button
            type="button"
            onClick={() => void logout()}
            className="rail-item relative flex h-11 w-11 items-center justify-center rounded-[14px] text-slate-500 hover:bg-white/[0.04] hover:text-slate-200"
            aria-label={`Sign out${username ? ` (${username})` : ""}`}
          >
            <IconLogout size={18} />
            <span className="rail-tip" role="tooltip">
              Sign out{username ? ` (${username})` : ""}
            </span>
          </button>
        ) : (
          <div className="flex h-11 w-11 items-center justify-center text-slate-600" title="Local session">
            <IconUser size={18} />
          </div>
        )}
      </div>
    </aside>
  );
}
