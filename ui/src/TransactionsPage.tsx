import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, type Account, type Category, type Transaction } from "./api";
import { Amount } from "./Amount";
import { CategoryOptions, CategoryPicker } from "./CategoryPicker";
import { kindLabel, longDate, monthBounds } from "./format";
import { NoteEditor, SplitEditor } from "./TransactionExtras";

/** Filtre de catégorie : une catégorie précise, les opérations à classer, ou tout. */
export type CategoryFilter = number | "uncategorized" | null;

type Props = {
  accounts: Account[];
  categories: Category[];
  accountId: number | null;
  categoryFilter: CategoryFilter;
  month: string;
  onCategoryFilterChange: (filter: CategoryFilter) => void;
  onMonthChange: (month: string) => void;
  onChanged: () => void;
  onImport: () => void;
};

export function TransactionsPage({
  accounts,
  categories,
  accountId,
  categoryFilter,
  month,
  onCategoryFilterChange,
  onMonthChange,
  onChanged,
  onImport,
}: Props) {
  const [query, setQuery] = useState("");
  const [transactions, setTransactions] = useState<Transaction[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkCategory, setBulkCategory] = useState("");
  const [bulkError, setBulkError] = useState<string | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);

  const toggle = (id: number) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const stopSelecting = () => {
    setSelecting(false);
    setSelected(new Set());
    setBulkError(null);
  };

  async function applyBulk() {
    if (selected.size === 0 || bulkCategory === "") return;
    setBulkBusy(true);
    setBulkError(null);
    try {
      await api.categorizeMany([...selected], bulkCategory === "auto" ? null : Number(bulkCategory));
      stopSelecting();
      await load();
      onChanged();
    } catch (e) {
      setBulkError(e instanceof ApiError ? e.message : "Le classement n'a pas été enregistré.");
    } finally {
      setBulkBusy(false);
    }
  }

  const load = useCallback(() => {
    const bounds = month ? monthBounds(month) : {};
    return api
      .transactions({
        accountId: accountId ?? undefined,
        q: query || undefined,
        categoryId: typeof categoryFilter === "number" ? categoryFilter : undefined,
        uncategorized: categoryFilter === "uncategorized",
        ...bounds,
      })
      .then(setTransactions)
      .catch(() => setTransactions([]));
  }, [accountId, month, query, categoryFilter]);

  useEffect(() => {
    const handle = window.setTimeout(load, 150);
    return () => window.clearTimeout(handle);
  }, [load]);

  const totals = useMemo(() => {
    const counted = (transactions ?? []).filter((t) => t.categoryKind !== "TRANSFER" && !t.internalTransfer);
    return {
      income: counted.filter((t) => t.amountCents > 0).reduce((s, t) => s + t.amountCents, 0),
      spending: counted.filter((t) => t.amountCents < 0).reduce((s, t) => s + t.amountCents, 0),
      excluded: (transactions ?? []).length - counted.length,
    };
  }, [transactions]);

  const days = useMemo(() => {
    const groups = new Map<string, Transaction[]>();
    for (const t of transactions ?? []) groups.set(t.operationDate, [...(groups.get(t.operationDate) ?? []), t]);
    return [...groups.entries()];
  }, [transactions]);

  const title = accounts.find((a) => a.id === accountId)?.name ?? "Toutes les opérations";

  if (accounts.length === 0) {
    return (
      <section className="empty">
        <h1>Aucune opération pour l'instant</h1>
        <p className="muted">Déposez un relevé de comptes La Banque Postale pour commencer.</p>
        <button className="primary" onClick={onImport}>
          Importer un relevé
        </button>
      </section>
    );
  }

  return (
    <section className="page">
      <header className="page-header">
        <h1>{title}</h1>
        <div className="filters">
          <label className="field field--inline">
            <span>Mois</span>
            <input type="month" value={month} onChange={(e) => onMonthChange(e.target.value)} />
          </label>
          {month && (
            <button className="link" onClick={() => onMonthChange("")}>
              Toutes les dates
            </button>
          )}
          <label className="field field--inline">
            <span>Catégorie</span>
            <select
              value={categoryFilter === null ? "" : String(categoryFilter)}
              onChange={(e) => {
                const value = e.target.value;
                onCategoryFilterChange(value === "" ? null : value === "uncategorized" ? "uncategorized" : Number(value));
              }}
            >
              <option value="">Toutes</option>
              <option value="uncategorized">À classer</option>
              <CategoryOptions categories={categories} />
            </select>
          </label>
          <label className="field field--inline field--grow">
            <span>Rechercher</span>
            <input type="search" placeholder="Libellé, référence…" value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
          <button className="secondary" onClick={() => (selecting ? stopSelecting() : setSelecting(true))} aria-pressed={selecting}>
            {selecting ? "Terminer la sélection" : "Classer plusieurs"}
          </button>
        </div>
      </header>

      <dl className="summary" aria-label="Totaux de la sélection">
        <div>
          <dt>Entrées</dt>
          <dd>
            <Amount cents={totals.income} signed />
          </dd>
        </div>
        <div>
          <dt>Sorties</dt>
          <dd>
            <Amount cents={totals.spending} signed />
          </dd>
        </div>
        <div>
          <dt>Solde de la sélection</dt>
          <dd>
            <Amount cents={totals.income + totals.spending} signed />
          </dd>
        </div>
        {totals.excluded > 0 && (
          <p className="summary-note">
            {totals.excluded} virement{totals.excluded > 1 ? "s" : ""} entre vos comptes exclu
            {totals.excluded > 1 ? "s" : ""} des totaux.
          </p>
        )}
      </dl>

      {transactions !== null && transactions.length === 0 && (
        <p className="muted">
          {categoryFilter === "uncategorized" ? "Tout est classé pour cette sélection." : "Aucune opération ne correspond à ces filtres."}
        </p>
      )}

      <div className="ledger">
        {days.map(([date, items]) => (
          <section key={date} className="ledger-day">
            <h2 className="ledger-date">{longDate(date)}</h2>
            <ul>
              {items.map((t) => (
                <li
                  key={t.id}
                  className={`ledger-row ${t.categoryKind === "TRANSFER" ? "ledger-row--internal" : ""} ${selecting ? "ledger-row--selecting" : ""}`}
                >
                  {selecting && (
                    <input
                      type="checkbox"
                      className="ledger-check"
                      checked={selected.has(t.id)}
                      onChange={() => toggle(t.id)}
                      aria-label={`Sélectionner ${t.label}`}
                    />
                  )}
                  <button
                    className="ledger-main"
                    aria-expanded={!selecting && open === t.id}
                    onClick={() => (selecting ? toggle(t.id) : setOpen(open === t.id ? null : t.id))}
                  >
                    <span className="ledger-label">{t.label}</span>
                    <span className="ledger-tags">
                      {t.splits.length > 0 ? (
                        <span className="tag tag--category" title={t.splits.map((p) => p.categoryName).join(", ")}>
                          Ventilée : {t.splits.map((p) => p.categoryName).join(" · ")}
                        </span>
                      ) : t.categoryName ? (
                        <span className={`tag tag--category ${t.categoryKind === "TRANSFER" ? "tag--internal" : ""}`}>
                          {t.categoryName}
                        </span>
                      ) : (
                        <span className="tag tag--todo">À classer</span>
                      )}
                      <span>{kindLabel[t.kind]}</span>
                      {accountId === null && <span className="ledger-account">{t.accountName}</span>}
                      {t.note && <span className="ledger-note">{t.note}</span>}
                    </span>
                    <Amount cents={t.amountCents} signed className="ledger-amount" />
                  </button>
                  {!selecting && open === t.id && (
                    <div className="ledger-details">
                      {t.bookingDate !== t.operationDate && <p>Comptabilisée le {longDate(t.bookingDate)}</p>}
                      {t.details && <pre>{t.details}</pre>}
                      {t.categorySource && t.categorySource !== "MANUAL" && (
                        <p>
                          {t.categorySource === "SPLIT"
                            ? `Ventilée : ${t.splits.map((p) => `${p.categoryName} ${(Math.abs(p.amountCents) / 100).toFixed(2).replace(".", ",")} €`).join(", ")}.`
                            : t.categorySource === "INTERNAL"
                            ? "Classée automatiquement : virement vers un de vos comptes."
                            : t.categorySource === "RULE"
                              ? "Classée automatiquement par une règle."
                              : "Classée automatiquement d'après le type d'opération."}
                        </p>
                      )}
                      {t.splits.length === 0 && (
                        <CategoryPicker
                          key={`${t.id}-${t.categoryId}`}
                          transaction={t}
                          categories={categories}
                          onSaved={() => {
                            load();
                            onChanged();
                          }}
                        />
                      )}
                      {!t.internalTransfer && (
                        <SplitEditor
                          key={`split-${t.id}-${t.splits.length}`}
                          transaction={t}
                          categories={categories}
                          onSaved={() => {
                            load();
                            onChanged();
                          }}
                        />
                      )}
                      <NoteEditor key={`note-${t.id}-${t.note ?? ""}`} transaction={t} onSaved={load} />
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>

      {selecting && (
        <div className="bulk-bar" role="region" aria-label="Classement en masse">
          <span>
            {selected.size} opération{selected.size > 1 ? "s" : ""} sélectionnée{selected.size > 1 ? "s" : ""}
          </span>
          <button className="link" onClick={() => setSelected(new Set((transactions ?? []).map((t) => t.id)))}>
            Tout sélectionner ({(transactions ?? []).length})
          </button>
          <select value={bulkCategory} onChange={(e) => setBulkCategory(e.target.value)} aria-label="Catégorie à appliquer">
            <option value="">Choisir une catégorie…</option>
            <option value="auto">Classement automatique</option>
            <CategoryOptions categories={categories} />
          </select>
          <button className="primary" onClick={applyBulk} disabled={bulkBusy || selected.size === 0 || bulkCategory === ""}>
            {bulkBusy ? "Classement…" : "Classer"}
          </button>
          {bulkError && <span className="form-error">{bulkError}</span>}
        </div>
      )}
    </section>
  );
}
