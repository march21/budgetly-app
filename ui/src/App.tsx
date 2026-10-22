import { useCallback, useEffect, useState } from "react";
import { api, VAULT_LOCKED_EVENT, type Account, type Category, type Rule, type VaultStatus } from "./api";
import { VaultScreen } from "./VaultScreen";
import { TransactionsPage, type CategoryFilter } from "./TransactionsPage";
import { DashboardPage } from "./DashboardPage";
import { CategoriesPage } from "./CategoriesPage";
import { PortfolioPage } from "./PortfolioPage";
import { ImportsPage } from "./ImportsPage";
import { SettingsPage } from "./SettingsPage";
import { RecurringPage } from "./RecurringPage";
import { Amount } from "./Amount";
import { currentMonth, longDate } from "./format";

type Page = "dashboard" | "recurring" | "portfolio" | "transactions" | "imports" | "categories" | "settings";

export function App() {
  const [vault, setVault] = useState<VaultStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refreshVault = useCallback(() => {
    api.vault().then(setVault).catch((e: Error) => setError(e.message));
  }, []);

  useEffect(() => {
    refreshVault();
    window.addEventListener(VAULT_LOCKED_EVENT, refreshVault);
    return () => window.removeEventListener(VAULT_LOCKED_EVENT, refreshVault);
  }, [refreshVault]);

  if (error) return <p className="boot-error">{error}</p>;
  if (!vault) return null;
  if (vault.state !== "UNLOCKED") return <VaultScreen status={vault} onUnlocked={refreshVault} />;
  return <Shell onLocked={refreshVault} minPasswordLength={vault.minPasswordLength} />;
}

function Shell({ onLocked, minPasswordLength }: { onLocked: () => void; minPasswordLength: number }) {
  const [page, setPage] = useState<Page>("dashboard");
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [rules, setRules] = useState<Rule[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>(null);
  const [dashboardMonth, setDashboardMonth] = useState(currentMonth());
  const [transactionsMonth, setTransactionsMonth] = useState("");
  const [revision, setRevision] = useState(0);

  const reload = useCallback(() => {
    api.accounts().then(setAccounts).catch(() => undefined);
    api.categories().then(setCategories).catch(() => undefined);
    api.rules().then(setRules).catch(() => undefined);
    setRevision((r) => r + 1);
  }, []);

  useEffect(reload, [reload]);

  // Verrouillage automatique côté cœur : l'interface le constate (sondage léger) et signale l'activité réelle.
  useEffect(() => {
    const poll = window.setInterval(() => {
      api.vault().then((v) => v.state !== "UNLOCKED" && onLocked()).catch(() => undefined);
    }, 20_000);
    let last = 0;
    const active = () => {
      const now = Date.now();
      if (now - last < 60_000) return;
      last = now;
      api.activity().catch(() => undefined);
    };
    window.addEventListener("pointerdown", active);
    window.addEventListener("keydown", active);
    window.addEventListener("wheel", active, { passive: true });
    return () => {
      window.clearInterval(poll);
      window.removeEventListener("pointerdown", active);
      window.removeEventListener("keydown", active);
      window.removeEventListener("wheel", active);
    };
  }, [onLocked]);

  const lock = () => api.lock().finally(onLocked);
  const total = accounts.reduce((sum, a) => sum + (a.balanceCents ?? 0), 0);

  const showTransactions = (filter: CategoryFilter, month: string) => {
    setSelected(null);
    setCategoryFilter(filter);
    setTransactionsMonth(month);
    setPage("transactions");
  };

  const navItems: { page: Page; label: string }[] = [
    { page: "dashboard", label: "Tableau de bord" },
    { page: "recurring", label: "Charges fixes" },
    { page: "portfolio", label: "Patrimoine" },
    { page: "transactions", label: "Opérations" },
    { page: "imports", label: "Importer un relevé" },
    { page: "categories", label: "Catégories et règles" },
    { page: "settings", label: "Réglages" },
  ];

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">Budgetly</div>

        <nav className="nav" aria-label="Sections">
          {navItems.map((item) => (
            <button
              key={item.page}
              className={page === item.page ? "active" : ""}
              aria-current={page === item.page ? "page" : undefined}
              onClick={() => setPage(item.page)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <section className="accounts" aria-label="Comptes">
          {accounts.length > 0 && (
            <button
              className={`account account--all ${selected === null && page === "transactions" ? "selected" : ""}`}
              onClick={() => {
                setSelected(null);
                setPage("transactions");
              }}
            >
              <span className="account-name">Tous les comptes</span>
              <Amount cents={total} className="account-balance" />
            </button>
          )}
          {accounts.map((account) => (
            <button
              key={account.id}
              className={`account ${selected === account.id && page === "transactions" ? "selected" : ""}`}
              onClick={() => {
                setSelected(account.id);
                setPage("transactions");
              }}
            >
              <span className="account-name">{account.name}</span>
              {account.balanceCents !== null && <Amount cents={account.balanceCents} className="account-balance" />}
              <span className="account-meta">
                {account.ibanMasked}
                {account.balanceDate && `, au ${longDate(account.balanceDate)}`}
              </span>
            </button>
          ))}
          {accounts.length === 0 && <p className="sidebar-empty">Vos comptes apparaîtront ici après le premier import.</p>}
        </section>

        <button className="lock" onClick={lock}>
          Verrouiller
        </button>
      </aside>

      <main className="main">
        {page === "dashboard" && (
          <DashboardPage
            key={revision}
            accounts={accounts}
            onImport={() => setPage("imports")}
            onShowRecurring={() => setPage("recurring")}
            month={dashboardMonth}
            onMonthChange={setDashboardMonth}
            onShowUncategorized={() => showTransactions("uncategorized", dashboardMonth)}
            onShowCategory={(id) => showTransactions(id, dashboardMonth)}
          />
        )}
        {page === "recurring" && <RecurringPage key={revision} onImport={() => setPage("imports")} />}
        {page === "portfolio" && <PortfolioPage onChanged={reload} onImport={() => setPage("imports")} />}
        {page === "transactions" && (
          <TransactionsPage
            accounts={accounts}
            categories={categories}
            accountId={selected}
            categoryFilter={categoryFilter}
            month={transactionsMonth}
            onCategoryFilterChange={setCategoryFilter}
            onMonthChange={setTransactionsMonth}
            onChanged={reload}
            onImport={() => setPage("imports")}
          />
        )}
        {page === "imports" && (
          <ImportsPage onImported={reload} onCancelled={reload} onShowTransactions={() => showTransactions(null, "")} />
        )}
        {page === "categories" && <CategoriesPage categories={categories} rules={rules} onChanged={reload} />}
        {page === "settings" && <SettingsPage minPasswordLength={minPasswordLength} />}
      </main>
    </div>
  );
}
