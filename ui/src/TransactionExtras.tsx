import { useState, type FormEvent } from "react";
import { api, ApiError, type Category, type Transaction } from "./api";
import { CategoryOptions } from "./CategoryPicker";
import { amount, parseEuros } from "./format";

type Part = { categoryId: string; euros: string };

/**
 * Ventilation d'une opération sur plusieurs catégories. Les montants sont saisis en positif ;
 * la dernière part se complète d'elle-même pour que la somme fasse le montant de l'opération.
 */
export function SplitEditor({ transaction, categories, onSaved }: { transaction: Transaction; categories: Category[]; onSaved: () => void }) {
  const total = Math.abs(transaction.amountCents);
  const sign = transaction.amountCents < 0 ? -1 : 1;
  const initial: Part[] =
    transaction.splits.length > 0
      ? transaction.splits.map((s) => ({ categoryId: String(s.categoryId), euros: (Math.abs(s.amountCents) / 100).toFixed(2).replace(".", ",") }))
      : [
          { categoryId: transaction.categoryId ? String(transaction.categoryId) : "", euros: "" },
          { categoryId: "", euros: "" },
        ];
  const [open, setOpen] = useState(transaction.splits.length > 0);
  const [parts, setParts] = useState<Part[]>(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Toutes les parts sauf la dernière sont saisies ; la dernière reçoit le reste.
  const entered = parts.slice(0, -1).map((p) => parseEuros(p.euros));
  const enteredSum = entered.reduce<number>((sum, c) => sum + (c && !Number.isNaN(c) ? c : 0), 0);
  const rest = total - enteredSum;
  const valid =
    parts.every((p) => p.categoryId !== "") &&
    entered.every((c) => c !== null && !Number.isNaN(c) && c > 0) &&
    rest > 0;

  const update = (index: number, patch: Partial<Part>) => setParts(parts.map((p, i) => (i === index ? { ...p, ...patch } : p)));

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    try {
      const cents = [...entered.map((c) => c as number), rest];
      await api.splitTransaction(
        transaction.id,
        parts.map((p, i) => ({ categoryId: Number(p.categoryId), amountCents: sign * cents[i] })),
      );
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La ventilation n'a pas été enregistrée.");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api.splitTransaction(transaction.id, []);
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La ventilation n'a pas été retirée.");
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button className="link" onClick={() => setOpen(true)}>
        Ventiler sur plusieurs catégories
      </button>
    );
  }

  return (
    <form className="split-editor" onSubmit={save} aria-label="Ventilation">
      <p className="muted">Répartissez les {amount(total)} ; la dernière ligne reçoit le reste.</p>
      {parts.map((part, i) => {
        const last = i === parts.length - 1;
        return (
          <div key={i} className="split-row">
            <select value={part.categoryId} onChange={(e) => update(i, { categoryId: e.target.value })} aria-label={`Catégorie ${i + 1}`}>
              <option value="">Catégorie…</option>
              <CategoryOptions categories={categories.filter((c) => !c.system)} />
            </select>
            {last ? (
              <span className={`split-rest ${rest <= 0 ? "split-rest--ko" : ""}`}>{amount(Math.max(rest, 0))}</span>
            ) : (
              <input inputMode="decimal" placeholder="Montant" value={part.euros} onChange={(e) => update(i, { euros: e.target.value })} aria-label={`Montant ${i + 1}`} />
            )}
            {parts.length > 2 && (
              <button type="button" className="link" onClick={() => setParts(parts.filter((_, j) => j !== i))} aria-label={`Retirer la part ${i + 1}`}>
                Retirer
              </button>
            )}
          </div>
        );
      })}
      <div className="picker-actions">
        <button type="button" className="link" onClick={() => setParts([...parts.slice(0, -1), { categoryId: "", euros: "" }, parts[parts.length - 1]])}>
          Ajouter une part
        </button>
      </div>
      {rest <= 0 && <p className="hint hint--warn">Les parts dépassent le montant de l'opération.</p>}
      {error && <p className="form-error">{error}</p>}
      <div className="picker-actions">
        <button className="primary" type="submit" disabled={!valid || busy}>
          Enregistrer la ventilation
        </button>
        {transaction.splits.length > 0 ? (
          <button type="button" className="secondary" onClick={remove} disabled={busy}>
            Défaire la ventilation
          </button>
        ) : (
          <button type="button" className="secondary" onClick={() => setOpen(false)} disabled={busy}>
            Annuler
          </button>
        )}
      </div>
    </form>
  );
}

/** Note libre sur une opération (retrouvée par la recherche). */
export function NoteEditor({ transaction, onSaved }: { transaction: Transaction; onSaved: () => void }) {
  const [text, setText] = useState(transaction.note ?? "");
  const [error, setError] = useState<string | null>(null);
  const changed = text.trim() !== (transaction.note ?? "");

  async function save(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.setNote(transaction.id, text.trim() || null);
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "La note n'a pas été enregistrée.");
    }
  }

  return (
    <form className="note-editor" onSubmit={save}>
      <label className="field">
        <span>Note</span>
        <input value={text} maxLength={500} placeholder="Ex. cadeau, remboursement attendu…" onChange={(e) => setText(e.target.value)} />
      </label>
      {changed && (
        <button className="secondary" type="submit">
          Enregistrer la note
        </button>
      )}
      {error && <p className="form-error">{error}</p>}
    </form>
  );
}
