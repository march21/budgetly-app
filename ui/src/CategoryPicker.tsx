import { useState } from "react";
import { api, ApiError, type Category, type Transaction } from "./api";
import { kindName } from "./format";

const kinds = ["EXPENSE", "INCOME", "SAVINGS", "TRANSFER"] as const;

/** Options groupées par nature, sous-catégories indentées sous leur catégorie. */
export function CategoryOptions({ categories }: { categories: Category[] }) {
  return (
    <>
      {kinds.map((kind) => {
        const tops = categories.filter((c) => c.kind === kind && c.parentId === null);
        if (tops.length === 0) return null;
        return (
          <optgroup key={kind} label={kindName[kind]}>
            {tops.flatMap((top) => [
              <option key={top.id} value={top.id}>
                {top.name}
              </option>,
              ...categories
                .filter((c) => c.parentId === top.id)
                .map((child) => (
                  <option key={child.id} value={child.id}>
                    {" "}
                    {child.name}
                  </option>
                )),
            ])}
          </optgroup>
        );
      })}
    </>
  );
}

type Props = { transaction: Transaction; categories: Category[]; onSaved: () => void };

export function CategoryPicker({ transaction, categories, onSaved }: Props) {
  const [categoryId, setCategoryId] = useState<string>(transaction.categoryId ? String(transaction.categoryId) : "");
  const [withRule, setWithRule] = useState(false);
  const [pattern, setPattern] = useState(transaction.suggestedPattern);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(next: number | null, rule?: string) {
    setBusy(true);
    setError(null);
    try {
      await api.categorize(transaction.id, next, rule);
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Le classement n'a pas été enregistré.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      className="picker"
      onSubmit={(e) => {
        e.preventDefault();
        if (categoryId) save(Number(categoryId), withRule ? pattern : undefined);
      }}
    >
      <label className="field">
        <span>Catégorie</span>
        <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="" disabled>
            Choisir une catégorie
          </option>
          <CategoryOptions categories={categories} />
        </select>
      </label>

      <label className="checkbox">
        <input type="checkbox" checked={withRule} onChange={(e) => setWithRule(e.target.checked)} />
        <span>Classer aussi les opérations dont le libellé contient</span>
      </label>
      {withRule && (
        <input
          className="picker-pattern"
          value={pattern}
          onChange={(e) => setPattern(e.target.value)}
          aria-label="Motif de la règle"
        />
      )}

      {error && <p className="form-error">{error}</p>}

      <div className="picker-actions">
        <button className="primary" type="submit" disabled={!categoryId || busy || (withRule && pattern.trim().length < 3)}>
          Classer
        </button>
        {transaction.categorySource === "MANUAL" && (
          <button className="secondary" type="button" disabled={busy} onClick={() => save(null)}>
            Revenir au classement automatique
          </button>
        )}
      </div>
    </form>
  );
}
