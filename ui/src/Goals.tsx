import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError, type Account, type Goal, type GoalRequest } from "./api";
import { amount, longDate, parseEuros, percent } from "./format";

const monthYear = new Intl.DateTimeFormat("fr-FR", { month: "long", year: "numeric" });
const monthText = (ym: string) => monthYear.format(new Date(`${ym}-15T12:00:00`));

function goalLine(g: Goal): string {
  if (g.reached) return "Objectif atteint.";
  const parts: string[] = [];
  if (g.targetDate && g.requiredMonthlyCents !== null) {
    parts.push(
      g.monthsLeft === 0
        ? `Il manque ${amount(g.targetCents - g.currentCents)} pour le ${longDate(g.targetDate)}.`
        : `${amount(g.requiredMonthlyCents)} par mois pour l'atteindre le ${longDate(g.targetDate)}.`,
    );
  }
  if (g.monthlyCents && g.projectedMonth) {
    const late = g.targetDate && g.projectedMonth > g.targetDate.slice(0, 7);
    parts.push(`À ${amount(g.monthlyCents)} par mois : atteint en ${monthText(g.projectedMonth)}${late ? ", après la date visée" : ""}.`);
  }
  if (parts.length === 0) parts.push(`Il reste ${amount(g.targetCents - g.currentCents)} à mettre de côté.`);
  return parts.join(" ");
}

export function GoalsPanel({ accounts }: { accounts: Account[] }) {
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [editing, setEditing] = useState<Goal | "new" | null>(null);
  const load = () => {
    api.goals().then(setGoals).catch(() => setGoals(null));
  };
  useEffect(load, [accounts]);

  if (!goals) return null;
  return (
    <section className="goals" aria-labelledby="goals-title">
      <div className="goals-header">
        <h2 id="goals-title">Objectifs d'épargne</h2>
        {editing === null && (
          <button className="secondary" onClick={() => setEditing("new")}>
            Ajouter un objectif
          </button>
        )}
      </div>
      {goals.length === 0 && editing === null && (
        <p className="muted">Fixez un montant à atteindre (épargne de précaution, apport, projet), suivi par le solde d'un compte ou à la main.</p>
      )}
      <ul>
        {goals.map((g) =>
          editing !== "new" && editing?.id === g.id ? (
            <li key={g.id}>
              <GoalForm accounts={accounts} goal={g} onDone={() => { setEditing(null); load(); }} />
            </li>
          ) : (
            <li key={g.id} className="goal">
              <div className="goal-head">
                <span className="cell-title">{g.name}</span>
                <span>
                  <strong>{amount(g.currentCents)}</strong> <span className="muted">sur {amount(g.targetCents)}</span>
                </span>
              </div>
              <div
                className="goal-bar"
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={g.targetCents}
                aria-valuenow={Math.min(g.currentCents, g.targetCents)}
                aria-label={`${g.name} : ${percent(Math.min(g.currentCents / g.targetCents, 1))}`}
              >
                <span style={{ width: `${Math.max(0, Math.min(100, (g.currentCents / g.targetCents) * 100))}%` }} className={g.reached ? "goal-bar--reached" : ""} />
              </div>
              <p className="goal-line">
                {goalLine(g)}{" "}
                <span className="muted">
                  {g.source === "ACCOUNT" ? `Suivi par ${g.accountName}.` : "Montant saisi."}
                </span>{" "}
                <button className="link" onClick={() => setEditing(g)}>
                  Modifier
                </button>
              </p>
            </li>
          ),
        )}
        {editing === "new" && (
          <li>
            <GoalForm accounts={accounts} onDone={() => { setEditing(null); load(); }} />
          </li>
        )}
      </ul>
    </section>
  );
}

function GoalForm({ accounts, goal, onDone }: { accounts: Account[]; goal?: Goal; onDone: () => void }) {
  const euros = (cents: number | null | undefined) => (cents == null ? "" : (cents / 100).toFixed(2).replace(".", ","));
  const [name, setName] = useState(goal?.name ?? "");
  const [target, setTarget] = useState(euros(goal?.targetCents));
  const [date, setDate] = useState(goal?.targetDate ?? "");
  const [accountId, setAccountId] = useState(goal?.accountId ? String(goal.accountId) : "manual");
  const [saved, setSaved] = useState(goal?.source === "MANUAL" ? euros(goal.currentCents) : "");
  const [monthly, setMonthly] = useState(euros(goal?.monthlyCents));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // parseEuros : montant positif en centimes, null si vide, NaN si illisible.
  const ok = (v: number | null) => v === null || Number.isFinite(v);
  const targetCents = parseEuros(target);
  const savedCents = parseEuros(saved) ?? 0;
  const monthlyCents = parseEuros(monthly);
  const valid = name.trim().length > 0 && targetCents !== null && Number.isFinite(targetCents) && targetCents > 0 && ok(savedCents) && ok(monthlyCents);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setBusy(true);
    setError(null);
    const body: GoalRequest = {
      name: name.trim(),
      targetCents: targetCents!,
      targetDate: date || null,
      accountId: accountId === "manual" ? null : Number(accountId),
      savedCents: accountId === "manual" ? savedCents : null,
      monthlyCents,
    };
    try {
      if (goal) await api.updateGoal(goal.id, body);
      else await api.createGoal(body);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "L'objectif n'a pas été enregistré.");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!goal) return;
    try {
      await api.deleteGoal(goal.id);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "L'objectif n'a pas été supprimé.");
    }
  }

  return (
    <form className="goal-form" onSubmit={submit} aria-label={goal ? `Modifier ${goal.name}` : "Nouvel objectif"}>
      <label className="field">
        <span>Nom</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Épargne de précaution" maxLength={80} />
      </label>
      <label className="field">
        <span>Montant visé</span>
        <input inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} placeholder="10 000" />
      </label>
      <label className="field">
        <span>Date visée (facultatif)</span>
        <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </label>
      <label className="field">
        <span>Suivi par</span>
        <select value={accountId} onChange={(e) => setAccountId(e.target.value)}>
          <option value="manual">Un montant que je saisis</option>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              Le solde de {a.name}
            </option>
          ))}
        </select>
      </label>
      {accountId === "manual" && (
        <label className="field">
          <span>Déjà mis de côté</span>
          <input inputMode="decimal" value={saved} onChange={(e) => setSaved(e.target.value)} placeholder="0" />
        </label>
      )}
      <label className="field">
        <span>Effort prévu par mois (facultatif)</span>
        <input inputMode="decimal" value={monthly} onChange={(e) => setMonthly(e.target.value)} placeholder="200" />
      </label>
      {error && <p className="form-error">{error}</p>}
      <div className="goal-form-actions">
        <button type="submit" className="primary" disabled={!valid || busy}>
          {goal ? "Enregistrer" : "Ajouter"}
        </button>
        <button type="button" onClick={onDone}>
          Annuler
        </button>
        {goal && (
          <button type="button" className="link link--danger" onClick={remove}>
            Supprimer l'objectif
          </button>
        )}
      </div>
    </form>
  );
}
